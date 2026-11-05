package com.example.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 비밀번호 변경 요청 본문.
public record PasswordChangeRequest(
        // 현재 비밀번호는 형식 규칙을 따지지 않고 "비어 있지 않음"만 본다(틀리면 서비스에서 400).
        // 다만 BCrypt 비교에 너무 긴 값이 들어가지 않도록 상한만 둔다.
        @NotBlank @Size(max = UserRules.PASSWORD_MAX) @Utf8MaxBytes(UserRules.PASSWORD_MAX_BYTES) String currentPassword,
        // 새 비밀번호는 가입과 같은 규칙
        @NotBlank @Size(min = UserRules.PASSWORD_MIN, max = UserRules.PASSWORD_MAX) @Utf8MaxBytes(UserRules.PASSWORD_MAX_BYTES) String newPassword) {

    // record 기본 toString은 비밀번호를 그대로 찍으므로, 로그에 새지 않게 가린다
    @Override
    public String toString() {
        return "PasswordChangeRequest[currentPassword=***, newPassword=***]";
    }
}
