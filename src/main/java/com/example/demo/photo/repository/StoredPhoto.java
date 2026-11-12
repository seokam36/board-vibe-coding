package com.example.demo.photo.repository;

import java.io.InputStream;

// 창고에서 "열어 둔" 사진 한 장: 읽을 수 있는 스트림 + 전체 길이(바이트).
// 파일 전체를 메모리에 올리지 않고 흘려보내려고 쓴다. 스트림은 받은 쪽이 반드시 닫아야 한다.
public record StoredPhoto(InputStream stream, long length) {
}
