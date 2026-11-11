package com.example.demo.market.repository;

import org.springframework.data.jpa.domain.Specification;

import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;

// 물건 글 목록 거르기 조건 세 가지. 각 값이 null이면 그 조건은 안 건 것이다. (VisaReviewFilter와 같은 방식)
// 학교가 실제로 있는지는 MarketItemService.getAll이 확인한 뒤에 만든다.
public record MarketItemFilter(Long schoolId, TradeType tradeType, ItemStatus status) {

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
        return spec;
    }
}
