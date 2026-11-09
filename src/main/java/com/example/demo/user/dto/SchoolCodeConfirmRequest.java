package com.example.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 학교 메일 인증 코드 확인 본문. 코드는 정확히 숫자 6자리(앞자리 0 가능).
public record SchoolCodeConfirmRequest(
        @NotBlank @Pattern(regexp = "[0-9]{6}", message = "인증 코드는 숫자 6자리여야 합니다.") String code) {

    // 검증 전에 앞뒤 공백을 지운다. null은 그대로 두어 @NotBlank가 400을 낸다.
    public SchoolCodeConfirmRequest {
        if (code != null) {
            code = code.trim();
        }
    }
}
