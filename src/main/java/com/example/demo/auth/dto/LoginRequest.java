package com.example.demo.auth.dto;

import jakarta.validation.constraints.NotBlank;

// 로그인 요청 본문. 형식 검증은 가입에서만 하고, 여기서는 빈 값만 400으로 거른다 (틀린 값은 401).
public record LoginRequest(@NotBlank String username, @NotBlank String password) {

    // record 기본 toString은 비밀번호를 그대로 찍으므로, 로그에 새지 않게 가린다
    @Override
    public String toString() {
        return "LoginRequest[username=" + username + ", password=***]";
    }
}
