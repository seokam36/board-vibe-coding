package com.example.demo.auth.dto;

import org.springframework.security.oauth2.jwt.Jwt;

import com.example.demo.user.domain.UserRole;

// "지금 로그인한 사람"을 나타내는 작은 값. 토큰에서 꺼낸 id, username, role만 담는다.
// JWT -> LoginUser 변환은 여기 한 곳에서만 한다. (S4-d에서 글 서비스가 이 값을 받아 작성자를 확인한다)
// 주의: role은 토큰에 든 값이라 최대 15분 묵을 수 있다. 관리자 권한은 쓰기 전에 DB로 다시 확인한다(AccessGuard).
public record LoginUser(Long id, String username, UserRole role) {

    public static final String USERNAME_CLAIM = "username";
    public static final String ROLE_CLAIM = "role";

    // sub 클레임(문자열 id), username·role 클레임을 읽는다
    public static LoginUser from(Jwt jwt) {
        return new LoginUser(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString(USERNAME_CLAIM),
                parseRole(jwt.getClaimAsString(ROLE_CLAIM)));
    }

    // role 클레임이 없거나(S9 이전에 발급된 옛 토큰) 모르는 값이면 일반 회원으로 본다
    private static UserRole parseRole(String value) {
        if (value == null) {
            return UserRole.USER;
        }
        try {
            return UserRole.valueOf(value);
        } catch (IllegalArgumentException e) {
            return UserRole.USER;
        }
    }
}
