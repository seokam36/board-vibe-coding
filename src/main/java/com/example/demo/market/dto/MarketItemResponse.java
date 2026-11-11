package com.example.demo.market.dto;

import java.time.Instant;
import java.util.List;

import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.MarketItemPhoto;
import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.user.dto.AuthorResponse;
import com.fasterxml.jackson.annotation.JsonInclude;

// 물건 글 응답. 엔티티를 그대로 내보내지 않는다.
// tradeType·status는 {code, name}, school은 {id, name}, seller는 {id, nickname}.
// photos는 한 건짜리 응답(단건·수정·상태 변경·올리기)에만 들어간다(올린 순, 없으면 []).
// 목록 응답은 글마다 사진 쿼리가 나가는 N+1을 피하려고 photos를 null로 두고, NON_NULL이라 JSON에서 아예 빠진다.
public record MarketItemResponse(Long id, String title, String content, MarketOptionResponse tradeType, int price,
        MarketOptionResponse status, SchoolResponse school, AuthorResponse seller, Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<MarketItemPhotoResponse> photos) {

    // 목록용: 사진 없이 (photos 필드 자체가 응답에서 빠진다)
    public static MarketItemResponse from(MarketItem item) {
        return from(item, null);
    }

    // 한 건짜리 응답용: 사진 목록을 같이 담는다
    public static MarketItemResponse from(MarketItem item, List<MarketItemPhoto> photos) {
        return new MarketItemResponse(item.getId(), item.getTitle(), item.getContent(),
                MarketOptionResponse.from(item.getTradeType()), item.getPrice(),
                MarketOptionResponse.from(item.getStatus()), SchoolResponse.from(item.getSchool()),
                AuthorResponse.from(item.getSeller()), item.getCreatedAt(),
                photos == null ? null : photos.stream().map(MarketItemPhotoResponse::from).toList());
    }
}
