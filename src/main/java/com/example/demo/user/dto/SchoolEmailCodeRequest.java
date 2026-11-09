package com.example.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 학교 메일 인증 코드 요청 본문. 메일 주소 최대 길이는 이메일 표준(254자)을 따른다.
public record SchoolEmailCodeRequest(@NotBlank @Email @Size(max = 254) String email) {

    // 검증 전에 앞뒤 공백을 지운다(복사·붙여넣기 대비). null은 그대로 두어 @NotBlank가 400을 낸다.
    public SchoolEmailCodeRequest {
        if (email != null) {
            email = email.trim();
        }
    }
}
