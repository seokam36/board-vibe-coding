package com.example.demo.photo.repository;

import java.util.Optional;

// 사진 파일을 보관하는 창고의 약속(인터페이스). 지금은 로컬 폴더 구현만 있고,
// 나중(S13-g6)에 S3 구현을 같은 약속으로 붙인다.
public interface PhotoStorage {

    // key 이름으로 사진 바이트를 저장한다(같은 key면 덮어씀). 안전하지 않은 key는 IllegalArgumentException.
    void put(String key, byte[] bytes, String contentType);

    // 사진을 꺼낸다. 없으면 Optional.empty
    Optional<StoredPhoto> get(String key);

    // 사진을 지운다. 이미 없어도 예외를 던지지 않는다.
    void delete(String key);

    // 꺼낸 사진: 바이트 + content-type
    record StoredPhoto(byte[] bytes, String contentType) {
    }
}
