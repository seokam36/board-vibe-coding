package com.example.demo.visa.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;

// 비자 일정 등록·수정 요청 본문: 체류 만료일 하나 ("2026-12-31" 형식 문자열, 형식이 틀리면 JSON 변환 단계에서 400).
// 비어 있으면(null) @Valid에서 400. "오늘~오늘+5년" 범위는 서비스가 서울 Clock으로 본다.
// @Future 등을 쓰지 않는 이유: 서버 OS 시간대(UTC) 기준이라 서울 밤에 "오늘"이 어긋난다(VisaReviewRequest와 같다).
public record VisaPlanRequest(@NotNull LocalDate expiryDate) {
}
