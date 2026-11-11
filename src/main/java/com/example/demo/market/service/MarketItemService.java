package com.example.demo.market.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
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
import com.example.demo.market.domain.TradeType;
import com.example.demo.market.dto.MarketItemRequest;
import com.example.demo.market.dto.MarketItemResponse;
import com.example.demo.market.repository.MarketItemFilter;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;

import lombok.RequiredArgsConstructor;

// 물건 글 비즈니스 로직. open-in-view=false이므로 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor
public class MarketItemService {

    // 판매자 탈퇴로 깨지는 FK 제약 이름 (V9). 이 이름일 때만 401로 바꾼다.
    private static final String SELLER_FK = "fk_market_items_seller";

    private static final int MAX_PRICE = 10_000_000; // 판매 가격 상한(원). DB CHECK와 같은 값

    private final MarketItemRepository marketItemRepository;
    private final SchoolRepository schoolRepository; // 목록의 학교 조건이 실제 있는 학교인지 확인
    private final AccessGuard accessGuard;

    // 물건 글을 저장하고 결과를 돌려준다. 판매자·학교는 요청 본문이 아니라 로그인 회원의 인증 정보에서만 가져온다.
    // 검사 순서(비자 후기와 같다): 회원 없음 401 -> 미인증 403 -> 가격 400.
    // 누가 쓸 수 있는지(401·403)를 먼저 보고, 그다음 입력 내용(400)을 본다.
    @Transactional
    public MarketItemResponse create(LoginUser loginUser, MarketItemRequest request) {
        User seller = accessGuard.requireVerified(loginUser);
        int price = checkedPrice(request.tradeType(), request.price());
        try {
            // id가 IDENTITY라 save() 안에서 바로 INSERT가 나가므로 FK 위반을 여기서 잡을 수 있다
            MarketItem saved = marketItemRepository.save(new MarketItem(seller, seller.getVerifiedSchool(),
                    request.title(), request.content(), request.tradeType(), price));
            return MarketItemResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 회원 조회 직후 탈퇴가 커밋되면 seller FK가 위반된다 -> "회원이 없음"과 같이 401.
            // 다른 제약 위반은 숨기지 않고 그대로 던진다.
            if (ConstraintViolations.violatedConstraint(e).filter(SELLER_FK::equals).isPresent()) {
                throw ApiErrors.loginRequired();
            }
            throw e;
        }
    }

    // 물건 글 한 건 조회(누구나). 없으면 404
    @Transactional(readOnly = true)
    public MarketItemResponse get(Long id) {
        return MarketItemResponse.from(marketItemRepository.findById(id)
                .orElseThrow(ApiErrors::marketItemNotFound));
    }

    // 목록을 최신순(id 내림차순)으로 조회 (페이징 없음). schoolId·tradeType·status는 선택이며, 넘긴 조건만 AND로 건다.
    // 없는 학교면 404(학교 확인 쿼리 +1). 판매자·학교는 repository의 EntityGraph가 join으로 같이 읽으므로
    // 글 수와 무관하게 목록 쿼리는 1번이다(N+1 없음).
    @Transactional(readOnly = true)
    public List<MarketItemResponse> getAll(Long schoolId, TradeType tradeType, ItemStatus status) {
        if (schoolId != null && !schoolRepository.existsById(schoolId)) {
            throw ApiErrors.schoolNotFound();
        }
        MarketItemFilter filter = new MarketItemFilter(schoolId, tradeType, status);
        return marketItemRepository.findAll(filter.toSpecification(), Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(MarketItemResponse::from)
                .toList();
    }

    // 글 수정(판매자 본인만). 검사 순서는 비자 후기 수정과 같다: 입력 400(가격 규칙) -> 없는 id 404 -> 본인 아님 403.
    // (본문 형식 400은 컨트롤러 @Valid가 이미 먼저 걸렀다.) 관리자도 남의 글은 못 고친다. 학교 인증은 요구하지 않는다(S10 규칙).
    // 영속 상태 엔티티를 바꾸면 UPDATE 가 나간다(dirty checking). 상태·학교·판매자는 안 바뀐다.
    // 읽은 뒤 UPDATE 가 나가기 전에 다른 쪽이 글을 지우고 커밋하면 UPDATE 가 0행이 된다 -> 404 (500 방지).
    @Transactional
    public MarketItemResponse update(Long id, LoginUser loginUser, MarketItemRequest request) {
        int price = checkedPrice(request.tradeType(), request.price());
        MarketItem item = marketItemRepository.findById(id).orElseThrow(ApiErrors::marketItemNotFound);
        if (!item.isSoldBy(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 올린 글만 수정할 수 있습니다.");
        }
        item.update(request.title(), request.content(), request.tradeType(), price);
        try {
            // 트랜잭션이 끝날 때까지 미루지 않고 지금 UPDATE 를 내보내서, 0행 예외를 여기서 잡을 수 있게 한다
            marketItemRepository.saveAndFlush(item);
        } catch (ObjectOptimisticLockingFailureException e) {
            // 0행 UPDATE = 그 사이 글이 지워짐. 이 예외만 404로 바꾸고 다른 예외는 숨기지 않고 그대로 던진다.
            throw ApiErrors.marketItemNotFound();
        }
        return MarketItemResponse.from(item);
    }

    // 글 삭제(DB 행 제거). 없으면 404, 판매자도 관리자도 아니면 403. (비자 후기 삭제와 같은 패턴)
    @Transactional
    public void delete(Long id, LoginUser loginUser) {
        MarketItem item = marketItemRepository.findById(id).orElseThrow(ApiErrors::marketItemNotFound);
        // 관리자 토큰이면 DB에서 role을 다시 확인한다(회원 없음 401, 강등 403)
        accessGuard.checkOwnerOrAdmin(item.isSoldBy(loginUser.id()), loginUser, "본인이 올린 글만 삭제할 수 있습니다.");
        // 권한 확인 뒤 동시에 다른 쪽이 먼저 지웠으면 0행이므로 404 (예외 500 방지)
        if (marketItemRepository.deleteByIdReturningCount(id) == 0) {
            throw ApiErrors.marketItemNotFound();
        }
    }

    // 거래 방식에 맞는 가격인지 확인하고 저장할 가격을 돌려준다.
    // 판매: 1~10,000,000 필수. 나눔: 없거나 0이면 0, 1 이상(또는 음수)이면 400.
    private int checkedPrice(TradeType tradeType, Integer price) {
        if (tradeType == TradeType.SALE) {
            if (price == null || price < 1 || price > MAX_PRICE) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "판매 가격은 1원 이상 10,000,000원 이하여야 합니다.");
            }
            return price;
        }
        if (price != null && price != 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "나눔은 가격을 받을 수 없습니다.");
        }
        return 0;
    }
}
