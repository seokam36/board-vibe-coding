package com.example.demo.user.domain;

import java.util.Optional;

// 회원 역할. USER = 일반 회원, ADMIN = 관리자(DB에서만 지정한다. 가입으로는 만들 수 없다)
public enum UserRole {
    USER, ADMIN;

    // 토큰의 role 클레임 문자열을 역할로 바꾼다. 없거나 모르는 값이면 empty ("유효한 role" 판단은 여기 한 곳)
    public static Optional<UserRole> fromClaim(String value) {
        for (UserRole role : values()) {
            if (role.name().equals(value)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
