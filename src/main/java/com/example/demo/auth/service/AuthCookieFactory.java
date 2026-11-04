package com.example.demo.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.example.demo.config.JwtProperties;

// access_token·refresh_token 쿠키를 만들고 지우는 일을 한 곳에 모은다.
// HttpOnly: JS가 못 읽는다(XSS로 토큰이 털리는 것 방지). SameSite=Lax: 다른 사이트에서 오는 요청에는 안 붙는다.
// Secure: HTTPS에서만 전송. 로컬(http)에서는 꺼야 하므로 설정값(app.cookie.secure)으로 받는다.
@Component
public class AuthCookieFactory {

    public static final String ACCESS_TOKEN_COOKIE = "access_token";
    public static final String REFRESH_TOKEN_COOKIE = "refresh_token";

    private final JwtProperties props;
    private final boolean secure;
    // 리프레시 쿠키 경로: 브라우저 기준 경로다. nginx/Vite가 /api를 떼고 백엔드엔 /auth로 넘기므로 기본값이 /api/auth.
    // 이 경로 밑 요청(재발급·로그아웃)에만 쿠키가 따라가서, 다른 API 요청에는 리프레시 토큰이 실리지 않는다.
    private final String refreshPath;

    public AuthCookieFactory(JwtProperties props,
            @Value("${app.cookie.secure:false}") boolean secure,
            @Value("${app.cookie.refresh-path:/api/auth}") String refreshPath) {
        this.props = props;
        this.secure = secure;
        this.refreshPath = refreshPath;
    }

    // 로그인 때: 토큰을 담은 쿠키. 수명은 토큰 수명과 같다(900초).
    public ResponseCookie create(String accessToken) {
        return base(ACCESS_TOKEN_COOKIE, accessToken, "/").maxAge(props.accessTokenExpiry()).build();
    }

    // 로그아웃 때: 같은 이름·경로로 Max-Age=0을 보내 브라우저가 쿠키를 지우게 한다.
    public ResponseCookie delete() {
        return base(ACCESS_TOKEN_COOKIE, "", "/").maxAge(0).build();
    }

    // 리프레시 토큰 쿠키. 수명은 Redis TTL과 같다(7일).
    public ResponseCookie createRefresh(String refreshToken) {
        return base(REFRESH_TOKEN_COOKIE, refreshToken, refreshPath).maxAge(props.refreshTokenExpiry()).build();
    }

    // 리프레시 쿠키 삭제: 설정할 때와 같은 경로여야 브라우저가 지운다.
    public ResponseCookie deleteRefresh() {
        return base(REFRESH_TOKEN_COOKIE, "", refreshPath).maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String name, String value, String path) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .path(path)
                .sameSite("Lax");
    }
}
