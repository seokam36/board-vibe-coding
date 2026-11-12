package com.example.demo.market.dto;

import java.time.Instant;

import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.MarketItemPhoto;
import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.user.dto.AuthorResponse;

// 물건 글 "목록" 전용 응답. 한 건 응답(MarketItemResponse)과 필드가 같되, photos 대신 대표 사진 한 장(coverPhoto)만 담는다.
// coverPhoto = 그 글에서 id가 가장 작은(처음 올린) 사진. 사진이 없으면 JSON에 "coverPhoto": null로 키가 항상 있다.
public record MarketItemListResponse(Long id, String title, String content, MarketOptionResponse tradeType, int price,
        MarketOptionResponse status, SchoolResponse school, AuthorResponse seller, Instant createdAt,
        MarketItemPhotoResponse coverPhoto) {

    // coverPhoto는 null이어도 된다 (사진 없는 글)
    public static MarketItemListResponse from(MarketItem item, MarketItemPhoto coverPhoto) {
        return new MarketItemListResponse(item.getId(), item.getTitle(), item.getContent(),
                MarketOptionResponse.from(item.getTradeType()), item.getPrice(),
                MarketOptionResponse.from(item.getStatus()), SchoolResponse.from(item.getSchool()),
                AuthorResponse.from(item.getSeller()), item.getCreatedAt(),
                coverPhoto == null ? null : MarketItemPhotoResponse.from(coverPhoto));
    }
}
