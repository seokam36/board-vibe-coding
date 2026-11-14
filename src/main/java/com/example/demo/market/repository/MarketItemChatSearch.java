package com.example.demo.market.repository;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.jpa.domain.Specification;

import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.MarketItem;

import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;

// 챗봇(S17-c)이 AI에게 줄 글을 찾는 조건. 목록 API(GET /market-items?q=)의 MarketItemFilter와는 일부러 따로 둔다
// (그쪽은 "제목만, 한 단어, AND"라 규칙이 다르고, 그쪽 동작을 건드리지 않으려고).
// 규칙: 거래완료(DONE)가 아닌 글(판매중·예약중) 중에서, 단어 중 하나라도 제목 또는 본문에 들어 있으면(OR) 찾는다. 대소문자 무시 부분 일치.
public final class MarketItemChatSearch {

    private static final char ESCAPE = MarketItemFilter.ESCAPE; // 목록 검색과 같은 표시를 쓴다(escapeLike와 짝)

    private MarketItemChatSearch() {
    }

    // keywords는 비어 있지 않아야 한다(비면 아무 글도 안 걸리는 조건이 된다). 단어 거르기·개수 제한은 호출한 쪽 몫이다.
    public static Specification<MarketItem> openItemsMatchingAny(List<String> keywords) {
        return (root, query, cb) -> {
            HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
            List<Predicate> anyMatch = new ArrayList<>();
            for (String keyword : keywords) {
                // %, _, \ 는 와일드카드가 아니라 글자 그대로 찾는다. 소문자 변환은 양쪽 다 DB가 해서 Java와 DB 차이로 어긋나지 않게 한다.
                Expression<String> pattern = cb.lower(hcb.value("%" + MarketItemFilter.escapeLike(keyword) + "%"));
                anyMatch.add(cb.like(cb.lower(root.get("title")), pattern, ESCAPE));
                anyMatch.add(cb.like(cb.lower(root.get("content")), pattern, ESCAPE));
            }
            // 거래완료는 "지금 살 수 없는 글"이라 AI에게 보여 주지 않는다
            Predicate open = root.get("status").in(ItemStatus.ON_SALE, ItemStatus.RESERVED);
            return cb.and(open, cb.or(anyMatch.toArray(new Predicate[0])));
        };
    }
}
