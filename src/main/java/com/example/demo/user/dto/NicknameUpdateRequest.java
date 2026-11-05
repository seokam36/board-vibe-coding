package com.example.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

// 닉네임 변경 요청 본문. 규칙은 가입과 같다.
public record NicknameUpdateRequest(
        @NotBlank @Pattern(regexp = UserRules.NICKNAME_REGEX, message = UserRules.NICKNAME_MESSAGE) String nickname) {
}
