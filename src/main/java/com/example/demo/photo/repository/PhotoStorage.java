package com.example.demo.photo.repository;

import java.util.Optional;

// 사진 파일을 보관하는 창고의 약속(인터페이스). 지금은 로컬 폴더 구현만 있고,
// 나중(S13-g6)에 S3 구현을 같은 약속으로 붙인다.
//
// 약속 두 가지:
// 1) content-type은 DB(market_item_photos.content_type) 값만 믿는다. 창고는 바이트만 주고받고,
//    get()은 content-type을 돌려주지 않는다(구현이 파일 내용으로 추측하지 않는다).
// 2) 저장·읽기·삭제가 실패하면(디스크 IO 오류, S3 오류 등) 구현은 PhotoStorageException으로 감싸서 던진다.
//    (안전하지 않은 key는 예외로 IllegalArgumentException을 그대로 던진다 - 이건 호출하는 쪽의 버그다.)
public interface PhotoStorage {

    // key 이름으로 사진 바이트를 저장한다(같은 key면 덮어씀). contentType은 S3처럼 파일에 꼬리표를 달 수 있는 구현이 쓰고, 로컬은 저장하지 않는다.
    void put(String key, byte[] bytes, String contentType);

    // 사진 바이트를 꺼낸다. 없으면 Optional.empty
    Optional<byte[]> get(String key);

    // 사진을 지운다. 이미 없어도 예외를 던지지 않는다.
    void delete(String key);
}
