package com.example.demo.photo.service;

// 사진 하나를 처리한 결과: 본 사진 + 썸네일. 각각 바이트와 형식·크기 정보를 가진다.
public record ProcessedImage(Variant main, Variant thumbnail) {

    // format은 "JPEG" 또는 "PNG", contentType은 "image/jpeg" 또는 "image/png"
    public record Variant(byte[] data, String format, String contentType, int width, int height) {
    }
}
