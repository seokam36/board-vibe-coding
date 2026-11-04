package com.example.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// application.properties의 app.jwt.* 설정을 담는다.
// secret: Base64로 인코딩한 서명 비밀키(환경변수 JWT_SECRET). 디코딩 후 32바이트 이상이어야 한다.
// accessTokenExpiry: 액세스 토큰 수명 (기본 15분)
// refreshTokenExpiry: 리프레시 토큰 수명 (기본 7일). Redis TTL과 refresh_token 쿠키 Max-Age가 이 값을 따른다.
@ConfigurationProperties("app.jwt")
public record JwtProperties(String secret,
        @DefaultValue("15m") Duration accessTokenExpiry,
        @DefaultValue("7d") Duration refreshTokenExpiry) {

    // record 기본 toString은 비밀키를 그대로 찍으므로 가린다
    @Override
    public String toString() {
        return "JwtProperties[secret=***, accessTokenExpiry=" + accessTokenExpiry
                + ", refreshTokenExpiry=" + refreshTokenExpiry + "]";
    }
}
