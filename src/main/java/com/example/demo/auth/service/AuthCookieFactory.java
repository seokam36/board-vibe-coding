package com.example.demo.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.example.demo.config.JwtProperties;

// access_token 쿠키를 만들고 지우는 일을 한 곳에 모은다.
// HttpOnly: JS가 못 읽는다(XSS로 토큰이 털리는 것 방지). SameSite=Lax: 다른 사이트에서 오는 요청에는 안 붙는다.
// Secure: HTTPS에서만 전송. 로컬(http)에서는 꺼야 하므로 설정값(app.cookie.secure)으로 받는다.
@Component
public class AuthCookieFactory {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";

    private final JwtProperties props;
    private final boolean secure;

    public AuthCookieFactory(JwtProperties props, @Value("${app.cookie.secure:false}") boolean secure) {
        this.props = props;
        this.secure = secure;
    }

    // 로그인 때: 토큰을 담은 쿠키. 수명은 토큰 수명과 같다(900초).
    public ResponseCookie create(String accessToken) {
        return base(accessToken).maxAge(props.accessTokenExpiry()).build();
    }

    // 로그아웃 때: 같은 이름·경로로 Max-Age=0을 보내 브라우저가 쿠키를 지우게 한다.
    public ResponseCookie delete() {
        return base("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(ACCESS_TOKEN_COOKIE, value)
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax");
    }
}
