package com.example.demo.permit.service;

import java.util.Optional;

// 허가서 사진에서 글자를 읽어 오는 "판독기"의 규격 (S20-d2). 사진 한 장(이미 JPEG/PNG로 처리된 바이트)을 받아 글자 전체를 돌려준다.
// 못 읽었으면(빈 결과) Optional.empty(). 실제 구현(구글 Vision)은 S20-e에서 만들고, 지금은 항상 "못 읽음"인 NoopPermitTextReader가 쓰인다.
// 호출하는 쪽(PermitAutoReview)은 DB 트랜잭션 밖에서 부른다. 읽은 글자는 개인정보라 구현도 로그에 남기지 않는다.
public interface PermitTextReader {

    Optional<String> read(byte[] image, String contentType);
}
