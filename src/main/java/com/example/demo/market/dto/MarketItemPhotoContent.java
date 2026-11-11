package com.example.demo.market.dto;

// 사진 파일 보기(GET) 결과: 파일 바이트와 종류. 종류(contentType)는 파일에서 추측하지 않고 DB 값을 그대로 쓴다.
public record MarketItemPhotoContent(byte[] bytes, String contentType) {
}
