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
// 조건은 openItemsByCondition 하나다(S17-e4에서 질문 단어 최신순 옛 조건을 지웠다: 비상구도 이 조건으로 질문 단어를 넘겨 찾는다).
// 정렬은 맞은 단어 수 많은 순이다(S17-e3).
public final class MarketItemChatSearch {

    private static final char ESCAPE = MarketItemFilter.ESCAPE; // 목록 검색과 같은 표시를 쓴다(escapeLike와 짝)

    private MarketItemChatSearch() {
    }

    // S17-e3: AI가 채운 양식(단어·최대 가격)으로 찾는 조건. 판매중·예약중 + 단어 OR + (있으면) 가격 이하. 나눔만 찾는 조건은 없다(S17-e4 결정: AI가 말하지 않은 나눔을 붙여 판매 글이 사라졌다).
    // 정렬도 여기서 정한다: 맞은 단어 수 많은 순 -> 최신순 -> id 큰 순. 정렬을 서비스의 sortBy가 아니라 이 조건 안에 둔 이유:
    // "맞은 단어 수"는 일반 칸이 아니라 계산식(CASE)이라 Sort 객체로 말할 수 없고, LIMIT 전에 DB가 정렬해야 하기 때문이다.
    // 이 조건은 정렬을 안에 갖고 있으니: findBy에서 sortBy를 쓰면 안 되고(이 정렬이 덮어써진다), DISTINCT도 쓰면 안 된다
    // (PostgreSQL은 DISTINCT일 때 ORDER BY 식이 SELECT 목록에 있어야 해서 이 쿼리를 거부한다).
    // keywords가 null이거나 비면 맞은 단어 수 식을 못 만들어 NPE(500)가 나므로, 처음에 호출 쪽 잘못으로 알린다.
    public static Specification<MarketItem> openItemsByCondition(List<String> keywords, Integer maxPrice) {
        if (keywords == null || keywords.isEmpty()) {
            throw new IllegalArgumentException("keywords는 비어 있을 수 없습니다.");
        }
        return (root, query, cb) -> {
            HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
            List<Predicate> wordHits = new ArrayList<>(); // 단어마다 "제목 또는 본문에 있다" 조건
            for (String keyword : keywords) {
                // %, _, \ 는 글자 그대로. 사용자 입력은 문자열에 이어 붙이지 않고 바인딩 파라미터로 들어간다.
                Expression<String> pattern = cb.lower(hcb.value("%" + MarketItemFilter.escapeLike(keyword) + "%"));
                wordHits.add(cb.or(cb.like(cb.lower(root.get("title")), pattern, ESCAPE),
                        cb.like(cb.lower(root.get("content")), pattern, ESCAPE)));
            }

            List<Predicate> where = new ArrayList<>();
            where.add(root.get("status").in(ItemStatus.ON_SALE, ItemStatus.RESERVED)); // 거래완료 제외(서버 고정)
            where.add(cb.or(wordHits.toArray(new Predicate[0])));
            if (maxPrice != null) {
                where.add(cb.lessThanOrEqualTo(root.<Integer>get("price"), maxPrice)); // 나눔(0원)도 자연히 포함된다
            }

            // 맞은 단어 수 = 단어마다 (있으면 1, 없으면 0)의 합. 한 단어가 제목·본문 양쪽에 있어도 한 번만 센다(위 OR 한 덩어리라서).
            Expression<Integer> score = null;
            for (Predicate hit : wordHits) {
                Expression<Integer> one = cb.<Integer>selectCase().when(hit, 1).otherwise(0);
                score = (score == null) ? one : cb.sum(score, one);
            }
            query.orderBy(cb.desc(score), cb.desc(root.get("createdAt")), cb.desc(root.get("id")));

            return cb.and(where.toArray(new Predicate[0]));
        };
    }
}
