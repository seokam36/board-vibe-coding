package com.example.demo.visa.dto;

import java.time.LocalDate;

import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaResult;
import com.example.demo.visa.domain.VisaType;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 비자 후기 작성·수정 요청 본문(같은 필드, 수정도 전부 새 값으로 바꾼다). 잘못된 입력은 @Valid 검증에서 400으로 걸러진다.
// 학교는 받지 않는다(schoolId 같은 모르는 필드는 무시): 서버가 작성자의 인증 학교를 복사한다.
// 날짜는 "2026-09-01" 형식 문자열(형식이 틀리면 JSON 변환 단계에서 400). 날짜끼리의 규칙은 서비스에서 본다.
// @PastOrPresent를 쓰지 않는 이유: 서버 OS 시간대(UTC) 기준이라 서울 밤에 "오늘"이 미래로 판정된다. 서비스가 서울 Clock으로 본다.
// content는 선택(없으면 서비스에서 ""). PostgreSQL은 NUL(\u0000) 문자를 저장하지 못해 500이 나므로 미리 막는다.
public record VisaReviewRequest(
        @NotNull VisaType visaType,
        @NotNull VisaApplicationType applicationType,
        @NotNull LocalDate receivedDate,
        @NotNull LocalDate resultDate,
        @NotNull VisaResult result,
        @Size(max = 2000) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String content) {
}
