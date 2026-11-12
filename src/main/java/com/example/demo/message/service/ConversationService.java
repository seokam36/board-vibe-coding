package com.example.demo.message.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.message.domain.Conversation;
import com.example.demo.message.domain.Message;
import com.example.demo.message.dto.ConversationStartResponse;
import com.example.demo.message.dto.MessageResponse;
import com.example.demo.message.repository.ConversationRepository;
import com.example.demo.message.repository.MessageRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

// 쪽지(대화방) 비즈니스 로직. 보내는 사람은 본문이 아니라 로그인 정보(토큰)에서만 가져온다.
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MarketItemRepository marketItemRepository;
    private final UserRepository userRepository;
    private final AccessGuard accessGuard;
    private final Clock clock;

    // 중고 글 판매자에게 쪽지를 보낸다. 이 글 + 이 구매자의 방이 있으면 거기에 붙이고, 없으면 방을 만든다.
    // 검사 순서(본문 400은 컨트롤러의 @Valid가 먼저 한다):
    //   회원 없음 401 -> 미인증 403 -> 없는 글 404 -> 다른 학교 403 -> 자기 글 400 -> (방이 없을 때만) 거래완료 409
    // 이미 방이 있으면 글이 거래완료여도 쪽지는 계속 보낼 수 있다(기존 대화 이어가기).
    @Transactional
    public ConversationStartResponse start(Long itemId, LoginUser loginUser, String content) {
        User buyer = accessGuard.requireVerified(loginUser);
        // findById가 판매자·학교를 같이 읽어 온다(EntityGraph)
        MarketItem item = marketItemRepository.findById(itemId).orElseThrow(ApiErrors::marketItemNotFound);
        accessGuard.requireSameSchool(buyer, item.getSchool());
        if (item.isSoldBy(buyer.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "자기 글에는 쪽지를 보낼 수 없습니다.");
        }

        // 방·쪽지 INSERT 전에 구매자·판매자 회원 행을 먼저 잡는다(탈퇴 UserService.withdraw와 같은 "회원 행 먼저" 순서).
        // withdraw는 회원 행을 FOR UPDATE로 잡은 채 글 삭제(CASCADE)·방 buyer_id/seller_id SET NULL로 글·방 행을 건드린다.
        // 우리가 INSERT부터 하면 FK 검사로 글·방 행을 먼저 잡고 회원 행을 기다리게 되어 서로 기다리는 고리(교착, 500)가 생긴다.
        // 회원 행부터 잡으면 어느 쪽이 먼저든 먼저 온 쪽이 끝나기를 첫 잠금에서 기다릴 뿐이라 고리가 없다.
        // 탈퇴가 이긴 경우 행이 없으니 구매자 없음 401, 판매자 없음 404(판매자 탈퇴는 글 삭제와 함께 일어난다).
        Long sellerId = item.getSeller().getId();
        List<Long> alive = userRepository.lockForKeyShare(List.of(buyer.getId(), sellerId));
        if (!alive.contains(buyer.getId())) {
            throw ApiErrors.loginRequired();
        }
        if (!alive.contains(sellerId)) {
            throw ApiErrors.marketItemNotFound();
        }

        // 방 번호와 쪽지가 같은 시각을 쓰도록 한 번만 잰다(DB 정밀도인 마이크로초로 맞춘다)
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            Conversation existing = conversationRepository.findByMarketItemIdAndBuyerId(itemId, buyer.getId())
                    .orElse(null);
            Long conversationId;
            boolean createdNow = false;
            if (existing != null) {
                conversationId = existing.getId();
            } else {
                // 새 대화는 거래완료 글에는 시작할 수 없다
                if (item.getStatus() == ItemStatus.DONE) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "거래가 끝난 글에는 새 쪽지를 시작할 수 없습니다.");
                }
                // 방이 없을 때만 INSERT. 동시에 같은 사람이 보내면 한쪽만 만들고 다른 쪽은 빈 값을 받는다(트랜잭션은 멀쩡).
                var created = conversationRepository.insertIfAbsent(itemId, item.getTitle(), buyer.getId(),
                        item.getSeller().getId(), now);
                if (created.isPresent()) {
                    conversationId = created.get();
                    createdNow = true;
                } else {
                    // 경쟁에서 졌다: 상대가 방금 만든 방을 다시 찾는다(상대 INSERT가 커밋된 뒤라 보인다).
                    // 그새 글이 지워져 방의 글 칸이 NULL이 됐다면 못 찾으니 글 404.
                    conversationId = conversationRepository.findByMarketItemIdAndBuyerId(itemId, buyer.getId())
                            .orElseThrow(ApiErrors::marketItemNotFound).getId();
                }
            }

            Message message = messageRepository.save(
                    new Message(conversationRepository.getReferenceById(conversationId), buyer, content, now));
            if (!createdNow) {
                // 기존 방이면 마지막 쪽지 시각을 갱신한다
                conversationRepository.touchLastMessageAt(conversationId, now);
            }
            return new ConversationStartResponse(conversationId, MessageResponse.of(message, true));
        } catch (DataIntegrityViolationException e) {
            // 확인 직후 글 삭제/회원 탈퇴가 커밋되면 FK 위반(23503) -> 제약 이름으로 구분
            String constraint = ConstraintViolations.violatedConstraint(e).orElse("");
            if (constraint.equals("fk_conversations_buyer") || constraint.equals("fk_messages_sender")) {
                throw ApiErrors.loginRequired();
            }
            // 판매자 탈퇴는 글 삭제(CASCADE)와 함께 일어나므로 글 404와 같게 본다
            if (constraint.equals("fk_conversations_item") || constraint.equals("fk_conversations_seller")) {
                throw ApiErrors.marketItemNotFound();
            }
            throw e; // 다른 제약 위반은 숨기지 않는다
        }
    }
}
