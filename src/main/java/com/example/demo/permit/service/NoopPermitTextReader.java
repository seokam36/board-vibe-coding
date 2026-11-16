package com.example.demo.permit.service;

import java.util.Optional;

// 꺼진 판독기(PERMIT_OCR_MODE=off, 기본): 항상 "못 읽음"을 돌려준다. 그래서 모든 신청이 UNREADABLE로 기록된 채 관리자 대기로 남는다.
// 빈 등록은 PermitOcrConfig가 한다(모드에 따라 이 클래스나 VisionPermitTextReader 중 하나만 등록).
// 테스트는 @Primary 가짜 판독기로 이 빈을 덮어쓴다.
public class NoopPermitTextReader implements PermitTextReader {

    @Override
    public Optional<String> read(byte[] image, String contentType) {
        return Optional.empty();
    }
}
