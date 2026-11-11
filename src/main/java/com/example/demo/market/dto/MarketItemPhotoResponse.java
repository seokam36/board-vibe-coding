package com.example.demo.market.dto;

import com.example.demo.market.domain.MarketItemPhoto;

// 사진 응답. 창고 이름(storageKey·thumbKey)은 일부러 내보내지 않는다 (사진 보기는 서버 API로, S13-g3).
public record MarketItemPhotoResponse(Long id, int width, int height, int thumbWidth, int thumbHeight,
        String contentType) {

    public static MarketItemPhotoResponse from(MarketItemPhoto photo) {
        return new MarketItemPhotoResponse(photo.getId(), photo.getWidth(), photo.getHeight(), photo.getThumbWidth(),
                photo.getThumbHeight(), photo.getContentType());
    }
}
