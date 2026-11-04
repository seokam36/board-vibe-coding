package com.example.demo.auth.dto;

import org.springframework.security.oauth2.jwt.Jwt;

// "지금 로그인한 사람"을 나타내는 작은 값. 토큰에서 꺼낸 id와 username만 담는다.
// JWT -> LoginUser 변환은 여기 한 곳에서만 한다. (S4-d에서 글 서비스가 이 값을 받아 작성자를 확인한다)
public record LoginUser(Long id, String username) {

    public static final String USERNAME_CLAIM = "username";

    // sub 클레임(문자열 id)과 username 클레임을 읽는다
    public static LoginUser from(Jwt jwt) {
        return new LoginUser(Long.valueOf(jwt.getSubject()), jwt.getClaimAsString(USERNAME_CLAIM));
    }
}
