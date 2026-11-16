package com.example.demo.permit.dto;

import com.example.demo.photo.repository.StoredPhoto;

// 관리자가 보는 허가서 사진 한 장: 열어 둔 스트림 + 종류(DB에 적어 둔 값). 스트림은 받은 쪽이 닫아야 한다.
public record PermitPhotoContent(String contentType, StoredPhoto photo) {
}
