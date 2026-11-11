package com.example.demo.market.dto;

import java.time.Instant;

import com.example.demo.market.domain.MarketItem;
import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.user.dto.AuthorResponse;

// 물건 글 응답. 엔티티를 그대로 내보내지 않는다.
// tradeType·status는 {code, name}, school은 {id, name}, seller는 {id, nickname}.
public record MarketItemResponse(Long id, String title, String content, MarketOptionResponse tradeType, int price,
        MarketOptionResponse status, SchoolResponse school, AuthorResponse seller, Instant createdAt) {

    public static MarketItemResponse from(MarketItem item) {
        return new MarketItemResponse(item.getId(), item.getTitle(), item.getContent(),
                MarketOptionResponse.from(item.getTradeType()), item.getPrice(),
                MarketOptionResponse.from(item.getStatus()), SchoolResponse.from(item.getSchool()),
                AuthorResponse.from(item.getSeller()), item.getCreatedAt());
    }
}
