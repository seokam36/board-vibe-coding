package com.example.demo.message.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.example.demo.message.dto.ConversationDetailResponse;
import com.example.demo.message.dto.ConversationItemInfo;
import com.example.demo.message.dto.ConversationPartnerInfo;
import com.example.demo.message.dto.ConversationStartResponse;
import com.example.demo.message.dto.ConversationSummaryResponse;
import com.example.demo.message.dto.LastMessageInfo;
import com.example.demo.message.dto.MessageResponse;
import com.example.demo.message.repository.ConversationRepository;
import com.example.demo.message.repository.LastMessageRow;
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
        // [S14-a 리뷰 "기존 방에 붙이는 중 판매자 탈퇴가 커밋되면 seller_id NULL 방에 201"은 이미 닫혀 있다]
        // 판매자 회원 행의 KEY SHARE를 커밋까지 쥐고 있으므로 판매자 탈퇴(FOR UPDATE)는 우리 커밋 뒤에야 진행된다.
        // 즉 "탈퇴 커밋 뒤에 도착한 쪽지"는 위 lock에서 판매자 행이 없어 404가 되고, 201이 나가는 쪽지는 전부 탈퇴보다 먼저 커밋된 것이다
        // (그 뒤 방의 seller_id가 NULL이 되는 것은 정상: 쪽지는 탈퇴 전에 보낸 것). 기존 방 경로도 이 잠금 뒤에 실행된다.
        Long sellerId = item.getSeller().getId();
        List<Long> alive = userRepository.lockForKeyShare(List.of(buyer.getId(), sellerId));
        if (!alive.contains(buyer.getId())) {
            throw ApiErrors.loginRequired();
        }
        if (!alive.contains(sellerId)) {
            throw ApiErrors.marketItemNotFound();
        }

        // 새 방을 만들 때 쓰는 시각(DB 정밀도인 마이크로초). 쪽지 시각은 방 행을 잡은 뒤 appendMessage가 따로 잰다.
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        try {
            Conversation existing = conversationRepository.findByMarketItemIdAndBuyerId(itemId, buyer.getId())
                    .orElse(null);
            Long conversationId;
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
                } else {
                    // 경쟁에서 졌다: 상대가 방금 만든 방을 다시 찾는다(상대 INSERT가 커밋된 뒤라 보인다).
                    // 그새 글이 지워져 방의 글 칸이 NULL이 됐다면 못 찾으니 글 404.
                    conversationId = conversationRepository.findByMarketItemIdAndBuyerId(itemId, buyer.getId())
                            .orElseThrow(ApiErrors::marketItemNotFound).getId();
                }
            }

            Message message = appendMessage(conversationId, buyer, content);
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

    // 쪽지 저장 + 방의 마지막 쪽지 시각 갱신을 한 곳에서 한다(첫 쪽지와 답장이 같이 쓴다).
    // 반드시 호출한 쪽의 트랜잭션 안에서, 회원 행 KEY SHARE 잠금을 잡은 뒤에 부른다.
    //
    // [잠금 순서 = 회원 행 -> 방 행 -> 쪽지 INSERT] 쪽지 INSERT 전에 방 행을 FOR NO KEY UPDATE로 먼저 잡는다.
    //   - 탈퇴(UserService.withdraw): 회원 행 FOR UPDATE -> (글 행) -> 방 행(SET NULL).  글 삭제: 글 행 -> 방 행(SET NULL).
    //     답장/첫 쪽지: 회원 행 KEY SHARE -> 방 행. 방 행은 언제나 맨 끝에 잡으므로, 방 행을 쥔 채 회원·글 행을 기다리는 쪽이 없다 = 고리 없음.
    //     (답장은 글 행을 건드리지 않고, 글 삭제·탈퇴는 회원 KEY SHARE를 필요로 하지 않으므로 글 삭제와 새 고리도 생기지 않는다)
    //   - 안 잡으면: INSERT의 FK 검사가 방 행에 약한 잠금을 잡은 채 뒤의 UPDATE에서 강한 잠금을 기다리는 구조가 된다.
    //     글 삭제(방 market_item_id SET NULL, 가장 센 잠금)가 끼면 교착 후보다. PostgreSQL이 "이미 자기 잠금이 있는 쪽"을
    //     대기 줄 뒤에 세우지 않아 실제로는 터지지 않지만(재현 테스트로 확인), 그 내부 규칙에 기대지 않으려고 처음부터 한 줄로 세운다.
    //   - 같은 방의 답장끼리도 여기서 차례대로 선다. 그래서 쪽지 시각(now)을 이 잠금 뒤에 재야
    //     "id는 작은데 created_at이 더 늦은" 쪽지가 생기지 않는다.
    // 새 방은 방금 우리가 INSERT한 행(아직 남에게 안 보임)이라 잠금이 바로 잡힌다.
    //
    // 주의: touchLastMessageAt은 UPDATE 한 문장이라 영속성 컨텍스트에 이미 읽어 둔 Conversation 엔티티의 lastMessageAt은
    // 옛 값 그대로다(clearAutomatically 없음). 갱신 뒤 같은 트랜잭션에서 방 엔티티를 다시 읽어 응답에 쓰지 말 것.
    // (새 방은 insertIfAbsent가 방 만들 때의 시각을 넣었고, 쪽지 시각은 그보다 몇 마이크로초 뒤라 touch가 마지막 쪽지 시각으로 맞춘다)
    private Message appendMessage(Long conversationId, User sender, String content) {
        if (conversationRepository.lockForNoKeyUpdate(conversationId).isEmpty()) {
            throw ApiErrors.conversationNotFound(); // 방이 그새 사라짐(정상 흐름에선 없다)
        }
        // 잠금을 잡은 뒤에 쪽지 시각을 잰다(DB 정밀도인 마이크로초로 맞춘다)
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Message message = messageRepository.save(
                new Message(conversationRepository.getReferenceById(conversationId), sender, content, now));
        conversationRepository.touchLastMessageAt(conversationId, now);
        return message;
    }

    // 기존 대화방에 답장(이어서 쪽지 보내기). 본문 400은 컨트롤러의 @Valid가 먼저 한다.
    // 검사 순서: 회원 없음 401 -> 미인증 403 -> 방 없음/참가자 아님 404 -> 상대 탈퇴 409 -> (회원 행 잠금) -> 저장
    // 글이 지워진 방, 거래완료 글의 방에도 보낼 수 있다(대화는 계속된다).
    //
    // [잠금 순서 = 회원 행 먼저] UserService.withdraw는 (1) 회원 행 FOR UPDATE를 맨 먼저 잡고 (2) 그 뒤 글·방·쪽지 행을 건드린다
    // (회원 삭제의 SET NULL/CASCADE). 우리가 방 행을 먼저 잡고 회원 행을 기다리면 "탈퇴: 회원 -> 방, 답장: 방 -> 회원" 고리(교착, 500)가 된다.
    // 그래서 방 행은 일반 SELECT(잠금 없음)로만 읽고, 쪽지를 넣기 전에 나와 상대의 회원 행을 id 순으로 FOR KEY SHARE로 먼저 잡는다.
    //   - 탈퇴가 먼저 회원 행을 잡았으면: 우리는 첫 잠금에서 기다릴 뿐(아무것도 안 쥠) -> 탈퇴 커밋 뒤 행이 없다 -> 나 401 / 상대 409.
    //   - 우리가 먼저 잡았으면: 탈퇴가 자기 첫 잠금(lockById)에서 우리 커밋을 기다린다(아무것도 안 쥠) -> 고리 없음.
    //   - 커밋까지 상대 회원 행을 쥐고 있으므로 "상대가 탈퇴한 뒤에 쪽지가 들어가는 틈"이 없다(쪽지가 먼저, 탈퇴가 나중).
    // 회원 행끼리는 KEY SHARE라 답장끼리 서로 막지 않는다. 방 행은 appendMessage가 쪽지 INSERT 전에 FOR NO KEY UPDATE로 잡아 한 줄로 세운다
    // (맨 마지막에 잡는 잠금이라 그 뒤엔 더 잡는 게 없다. 근거는 appendMessage 주석).
    @Transactional
    public MessageResponse reply(Long conversationId, LoginUser loginUser, String content) {
        User me = accessGuard.requireVerified(loginUser);
        Conversation room = conversationRepository.findWithParticipantsById(conversationId)
                .orElseThrow(ApiErrors::conversationNotFound);
        if (!room.hasParticipant(me.getId())) {
            throw ApiErrors.conversationNotFound(); // 남의 방은 관리자도 없는 방과 똑같이 404
        }
        User partner = room.partnerOf(me.getId());
        if (partner == null) {
            throw partnerWithdrawn(); // 이미 탈퇴한 상대에게는 보낼 수 없다
        }

        List<Long> alive = userRepository.lockForKeyShare(List.of(me.getId(), partner.getId()));
        if (!alive.contains(me.getId())) {
            throw ApiErrors.loginRequired(); // 읽은 뒤 내가 탈퇴함
        }
        if (!alive.contains(partner.getId())) {
            throw partnerWithdrawn(); // 읽은 뒤 상대가 탈퇴함(그 커밋이 여기서 보인다)
        }

        try {
            return MessageResponse.of(appendMessage(conversationId, me, content), true);
        } catch (DataIntegrityViolationException e) {
            // 위에서 회원 행을 잡았으니 보낸 사람 FK는 깨질 수 없다. 방은 지워지지 않지만 만약을 위해 제약 이름으로 구분한다.
            String constraint = ConstraintViolations.violatedConstraint(e).orElse("");
            if (constraint.equals("fk_messages_sender")) {
                throw ApiErrors.loginRequired();
            }
            if (constraint.equals("fk_messages_conversation")) {
                throw ApiErrors.conversationNotFound();
            }
            throw e;
        }
    }

    // 내 대화 목록(최근 쪽지 순). 학교 인증은 필요 없다(읽기는 참가자면 된다). 회원이 DB에 없으면 401.
    // 쿼리 수는 방 개수와 무관하게 고정: 회원 1 + 방(상대 닉네임 포함) 1 + 마지막 쪽지 1.
    @Transactional(readOnly = true)
    public List<ConversationSummaryResponse> list(LoginUser loginUser) {
        User me = accessGuard.requireUser(loginUser);
        List<Conversation> rooms = conversationRepository.findAllOfUser(me.getId());
        if (rooms.isEmpty()) {
            return List.of();
        }
        Map<Long, LastMessageRow> lastByRoom = messageRepository
                .findLastMessages(rooms.stream().map(Conversation::getId).toList()).stream()
                .collect(Collectors.toMap(LastMessageRow::conversationId, Function.identity()));
        return rooms.stream().map(room -> {
            LastMessageRow last = lastByRoom.get(room.getId());
            // 방은 만들 때 쪽지 1통이 같이 저장되므로 last가 없는 일은 없지만, 없어도 목록이 깨지지 않게 null로 둔다
            LastMessageInfo lastMessage = last == null ? null
                    : new LastMessageInfo(last.content(), last.createdAt(), me.getId().equals(last.senderId()));
            return new ConversationSummaryResponse(room.getId(), ConversationItemInfo.of(room),
                    ConversationPartnerInfo.of(room, me.getId()), lastMessage);
        }).toList();
    }

    // 대화 한 건 + 쪽지 전체(id 오름차순). 참가자가 아니거나(관리자 포함) 없는 방이면 404.
    @Transactional(readOnly = true)
    public ConversationDetailResponse detail(Long conversationId, LoginUser loginUser) {
        User me = accessGuard.requireUser(loginUser);
        Conversation room = conversationRepository.findWithParticipantsById(conversationId)
                .orElseThrow(ApiErrors::conversationNotFound);
        if (!room.hasParticipant(me.getId())) {
            throw ApiErrors.conversationNotFound();
        }
        return new ConversationDetailResponse(room.getId(), ConversationItemInfo.of(room),
                ConversationPartnerInfo.of(room, me.getId()), messageRepository.findResponses(conversationId, me.getId()));
    }

    private ResponseStatusException partnerWithdrawn() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "상대가 탈퇴해 쪽지를 보낼 수 없습니다.");
    }
}
