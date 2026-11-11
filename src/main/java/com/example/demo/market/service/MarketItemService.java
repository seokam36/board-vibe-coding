package com.example.demo.market.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;
import com.example.demo.market.dto.MarketItemRequest;
import com.example.demo.market.dto.MarketItemResponse;
import com.example.demo.market.repository.MarketItemRepository;
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
