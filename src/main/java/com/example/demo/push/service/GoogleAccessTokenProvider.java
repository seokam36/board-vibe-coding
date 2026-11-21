package com.example.demo.push.service;

import java.io.IOException;

import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;

// 서비스 계정 키(GoogleCredentials)로 구글 액세스 토큰을 받는다 (S24-b).
// refreshIfExpired: 아직 안 만료됐으면 가진 걸 그대로 쓰고, 만료 직전이면 새로 받는다. 그래서 매번 구글을 부르지 않는다.
public class GoogleAccessTokenProvider implements AccessTokenProvider {

    private final GoogleCredentials credentials;

    public GoogleAccessTokenProvider(GoogleCredentials credentials) {
        this.credentials = credentials;
    }

    @Override
    public String accessToken() throws IOException {
        credentials.refreshIfExpired();
        AccessToken token = credentials.getAccessToken();
        if (token == null || token.getTokenValue() == null) {
            throw new IOException("액세스 토큰을 받지 못함");
        }
        return token.getTokenValue();
    }

    // 키 내용이 로그에 섞여 나오지 않게 아무것도 보여 주지 않는다
    @Override
    public String toString() {
        return "GoogleAccessTokenProvider";
    }
}
