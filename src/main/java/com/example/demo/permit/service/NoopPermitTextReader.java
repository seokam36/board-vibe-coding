package com.example.demo.permit.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

// 운영 기본 판독기: 항상 "못 읽음"을 돌려준다. 그래서 S20-e(Vision)가 붙기 전까지는 모든 신청이 UNREADABLE로 기록된 채 관리자 대기로 남는다.
// 테스트는 @Primary 가짜 판독기로 이 빈을 덮어쓴다.
@Component
public class NoopPermitTextReader implements PermitTextReader {

    @Override
    public Optional<String> read(byte[] image, String contentType) {
        return Optional.empty();
    }
}
