package com.example.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 회원 탈퇴 요청 본문. 본인 확인용 현재 비밀번호만 받는다.
public record WithdrawRequest(
        // 형식 규칙은 따지지 않고 "비어 있지 않음"만 본다(틀리면 서비스에서 400). BCrypt에 너무 긴 값이 가지 않게 상한만 둔다.
        @NotBlank @Size(max = UserRules.PASSWORD_MAX) @Utf8MaxBytes(UserRules.PASSWORD_MAX_BYTES) String password) {

    // record 기본 toString은 비밀번호를 그대로 찍으므로, 로그에 새지 않게 가린다
    @Override
    public String toString() {
        return "WithdrawRequest[password=***]";
    }
}
