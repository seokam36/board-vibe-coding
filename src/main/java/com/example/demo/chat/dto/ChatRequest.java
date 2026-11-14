package com.example.demo.chat.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 챗봇 질문 요청 본문. 비었거나 공백뿐이면 400, NUL 문자(PostgreSQL이 못 다루고 AI에도 쓸모없음) 400,
// 앞뒤 공백을 뺀 길이가 500자(코드포인트 기준)를 넘으면 400. 모양 검사는 @Valid가 한다.
public record ChatRequest(
        @NotBlank @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String question) {

    public static final int MAX_QUESTION_LENGTH = 500;

    // 앞뒤 공백을 뺀 길이 검사. @Size는 공백을 안 빼서 직접 한다. question이 null이면 @NotBlank가 잡으니 여기선 통과시킨다.
    @JsonIgnore
    @AssertTrue(message = "질문은 500자 이하여야 합니다")
    public boolean isQuestionLengthOk() {
        if (question == null) {
            return true;
        }
        String stripped = question.strip();
        return stripped.codePointCount(0, stripped.length()) <= MAX_QUESTION_LENGTH;
    }
}
