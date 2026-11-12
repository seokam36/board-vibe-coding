package com.example.demo.market.dto;

import java.time.Instant;
import java.util.List;

import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.MarketItemPhoto;
import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.user.dto.AuthorResponse;

// 물건 글 한 건짜리 응답(단건·올리기·수정·상태 변경). 엔티티를 그대로 내보내지 않는다.
// tradeType·status는 {code, name}, school은 {id, name}, seller는 {id, nickname}.
// photos는 항상 있다(올린 순, 없으면 []). 목록은 별도 DTO(MarketItemListResponse)를 쓴다.
public record MarketItemResponse(Long id, String title, String content, MarketOptionResponse tradeType, int price,
        MarketOptionResponse status, SchoolResponse school, AuthorResponse seller, Instant createdAt,
        List<MarketItemPhotoResponse> photos) {

    public static MarketItemResponse from(MarketItem item, List<MarketItemPhoto> photos) {
        return new MarketItemResponse(item.getId(), item.getTitle(), item.getContent(),
                MarketOptionResponse.from(item.getTradeType()), item.getPrice(),
                MarketOptionResponse.from(item.getStatus()), SchoolResponse.from(item.getSchool()),
                AuthorResponse.from(item.getSeller()), item.getCreatedAt(),
                photos.stream().map(MarketItemPhotoResponse::from).toList());
    }
}
