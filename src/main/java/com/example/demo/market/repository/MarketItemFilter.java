package com.example.demo.market.repository;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;

// 물건 글 목록 거르기 조건 네 가지. 각 값이 null이면 그 조건은 안 건 것이다. (VisaReviewFilter와 같은 방식)
// 학교가 실제로 있는지, 검색어(title)가 다듬어졌는지·길이가 맞는지는 MarketItemService.getAll이 확인한 뒤에 만든다.
// title = 제목 검색어(앞뒤 공백을 뺀 값, 없으면 null)
public record MarketItemFilter(Long schoolId, TradeType tradeType, ItemStatus status, String title) {

    private static final char ESCAPE = '\\'; // LIKE에서 "다음 글자는 그대로 찾아라"를 뜻하는 표시

    // 넘긴 조건만 AND로 건 Specification을 만든다
    public Specification<MarketItem> toSpecification() {
        Specification<MarketItem> spec = Specification.unrestricted();
        if (schoolId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("school").get("id"), schoolId));
        }
        if (tradeType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("tradeType"), tradeType));
        }
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (title != null) {
            // 제목만, 대소문자 무시 부분 일치: lower(title) like lower('%검색어%') escape '\'
            // 소문자 변환을 양쪽 다 DB가 하게 해서 Java와 DB의 변환 차이(예: İ)로 어긋나지 않게 한다
            String pattern = "%" + escapeLike(title) + "%";
            spec = spec.and((root, query, cb) -> cb.like(cb.lower(root.get("title")),
                    cb.lower(((HibernateCriteriaBuilder) cb).value(pattern)), ESCAPE));
        }
        return spec;
    }

    // LIKE의 특수 글자(\ % _)를 앞에 \를 붙여 "그냥 글자"로 만든다. \를 가장 먼저 바꿔야 새로 붙인 \가 또 바뀌지 않는다.
    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
