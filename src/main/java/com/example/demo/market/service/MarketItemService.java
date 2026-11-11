package com.example.demo.market.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
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
import com.example.demo.market.domain.MarketItemPhoto;
import com.example.demo.market.domain.TradeType;
import com.example.demo.market.dto.MarketItemRequest;
import com.example.demo.market.dto.MarketItemResponse;
import com.example.demo.market.dto.MarketItemStatusRequest;
import com.example.demo.market.repository.MarketItemFilter;
import com.example.demo.market.repository.MarketItemPhotoRepository;
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
    private static final int MAX_SEARCH_LENGTH = 50; // 제목 검색어 최대 글자 수

    private final MarketItemRepository marketItemRepository;
    private final MarketItemPhotoRepository photoRepository; // 한 건짜리 응답에 사진 목록을 붙인다 (S13-g2)
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
            return MarketItemResponse.from(saved, List.of()); // 방금 올린 글이라 사진은 아직 없다
        } catch (DataIntegrityViolationException e) {
            // 회원 조회 직후 탈퇴가 커밋되면 seller FK가 위반된다 -> "회원이 없음"과 같이 401.
            // 다른 제약 위반은 숨기지 않고 그대로 던진다.
            if (ConstraintViolations.violatedConstraint(e).filter(SELLER_FK::equals).isPresent()) {
                throw ApiErrors.loginRequired();
            }
            throw e;
        }
    }

    // 물건 글 한 건 조회(누구나). 없으면 404. 사진 목록(올린 순)을 같이 담는다 -> 쿼리 2번(글+판매자·학교 join 1번, 사진 1번)
    @Transactional(readOnly = true)
    public MarketItemResponse get(Long id) {
        return withPhotos(marketItemRepository.findById(id).orElseThrow(ApiErrors::marketItemNotFound));
    }

    // 글 하나를 사진 목록과 함께 응답으로 바꾼다 (한 건짜리 응답 전용. 목록에는 쓰지 않는다: 글마다 쿼리가 나가는 N+1)
    private MarketItemResponse withPhotos(MarketItem item) {
        List<MarketItemPhoto> photos = photoRepository.findByMarketItemIdOrderByIdAsc(item.getId());
        return MarketItemResponse.from(item, photos);
    }

    // 목록을 최신순(id 내림차순)으로 조회 (페이징 없음). schoolId·tradeType·status·q는 선택이며, 넘긴 조건만 AND로 건다.
    // q(제목 검색어)는 앞뒤 공백을 빼고, 비면 조건 없음, 50자(코드포인트 기준)를 넘거나 NUL 문자가 있으면 400. 입력 400을 학교 404보다 먼저 본다.
    // 없는 학교면 404(학교 확인 쿼리 +1). 판매자·학교는 repository의 EntityGraph가 join으로 같이 읽으므로
    // 글 수와 무관하게 목록 쿼리는 1번이다(N+1 없음).
    @Transactional(readOnly = true)
    public List<MarketItemResponse> getAll(Long schoolId, TradeType tradeType, ItemStatus status, String q) {
        String title = checkedSearchTerm(q);
        if (schoolId != null && !schoolRepository.existsById(schoolId)) {
            throw ApiErrors.schoolNotFound();
        }
        MarketItemFilter filter = new MarketItemFilter(schoolId, tradeType, status, title);
        return marketItemRepository.findAll(filter.toSpecification(), Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(MarketItemResponse::from)
                .toList();
    }

    // 검색어를 다듬는다: null·공백뿐이면 null(조건 없음), 앞뒤 공백 제거 후 50자(코드포인트) 초과면 400
    private String checkedSearchTerm(String q) {
        if (q == null) {
            return null;
        }
        String term = q.strip();
        if (term.isEmpty()) {
            return null;
        }
        // PostgreSQL은 NUL(\u0000) 문자를 처리하지 못해 500이 나므로 미리 400으로 막는다 (글 DTO와 같은 이유)
        if (term.indexOf('\u0000') >= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어에 쓸 수 없는 문자가 있습니다.");
        }
        if (term.codePointCount(0, term.length()) > MAX_SEARCH_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "검색어는 50자 이하여야 합니다.");
        }
        return term;
    }

    // 글 수정(판매자 본인만). 검사 순서는 비자 후기 수정과 같다: 입력 400(가격 규칙) -> 없는 id 404 -> 본인 아님 403.
    // (본문 형식 400은 컨트롤러 @Valid가 이미 먼저 걸렀다.) 관리자도 남의 글은 못 고친다. 학교 인증은 요구하지 않는다(S10 규칙).
    // 거래완료(DONE) 글은 내용을 못 고친다 -> 409. 검사 순서에서 본인 확인(403) 뒤에 본다.
    // 엔티티를 고쳐 저장하면 status까지 옛 값으로 쓸 수 있어서, "DONE이 아닐 때만" 고치는 조건부 UPDATE 한 문장을 쓴다.
    // 결과 행 수가 1이면 내가 고친 것, 0이면 그 사이 글이 지워졌거나(404) DONE이 된 것(409)이다.
    @Transactional
    public MarketItemResponse update(Long id, LoginUser loginUser, MarketItemRequest request) {
        int price = checkedPrice(request.tradeType(), request.price());
        MarketItem item = marketItemRepository.findById(id).orElseThrow(ApiErrors::marketItemNotFound);
        if (!item.isSoldBy(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 올린 글만 수정할 수 있습니다.");
        }
        if (marketItemRepository.updateContentIfNotDone(id, request.title(), request.content(), request.tradeType(),
                price) == 0) {
            // 0행 = 없거나(읽은 뒤 삭제됨) 거래완료. 존재 여부로 404/409를 가른다.
            if (!marketItemRepository.existsById(id)) {
                throw ApiErrors.marketItemNotFound();
            }
            throw doneItemConflict();
        }
        // 내가 고쳤다. 바뀐 글을 다시 읽어 돌려준다(그 사이 삭제됐으면 404).
        return withPhotos(marketItemRepository.findById(id).orElseThrow(ApiErrors::marketItemNotFound));
    }

    // 상태 바꾸기(판매자 본인만, 관리자도 남의 글은 못 바꾼다. 학교 인증 불필요).
    // 검사 순서: (CSRF 403, 로그인 401, 본문 400은 앞단에서 이미) -> 없는 id 404 -> 본인 아님 403 -> 전환 불가 409.
    // 바꾸는 방법은 "지금 상태가 from일 때만 to로" 조건부 UPDATE. 동시에 두 번 눌러도 하나만 1행을 받고 나머지는 0행(409).
    @Transactional
    public MarketItemResponse changeStatus(Long id, LoginUser loginUser, MarketItemStatusRequest request) {
        MarketItem item = marketItemRepository.findById(id).orElseThrow(ApiErrors::marketItemNotFound);
        if (!item.isSoldBy(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 올린 글만 상태를 바꿀 수 있습니다.");
        }
        ItemStatus from = item.getStatus();
        ItemStatus to = request.status();
        if (!from.canChangeTo(to)) {
            throw statusConflict();
        }
        if (marketItemRepository.changeStatus(id, from, to) == 0) {
            // 0행 = 없거나(삭제됨) 그 사이 다른 요청이 상태를 먼저 바꿈. 존재 여부로 404/409를 가른다.
            if (!marketItemRepository.existsById(id)) {
                throw ApiErrors.marketItemNotFound();
            }
            throw statusConflict();
        }
        // 내가 바꿨다. 바뀐 글을 다시 읽어 돌려준다(그 사이 삭제됐으면 404).
        return withPhotos(marketItemRepository.findById(id).orElseThrow(ApiErrors::marketItemNotFound));
    }

    private ResponseStatusException statusConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "바꿀 수 없는 상태 전환입니다.");
    }

    private ResponseStatusException doneItemConflict() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "거래완료된 글은 수정할 수 없습니다.");
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
