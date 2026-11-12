package com.example.demo.market.dto;

import com.example.demo.photo.repository.StoredPhoto;

// 사진 파일 보기(GET) 결과: 열린 파일 스트림(+길이)과 종류. 종류(contentType)는 파일에서 추측하지 않고 DB 값을 그대로 쓴다.
// 스트림은 응답을 다 쓴 뒤 Spring이 닫는다.
public record MarketItemPhotoContent(StoredPhoto photo, String contentType) {
}
