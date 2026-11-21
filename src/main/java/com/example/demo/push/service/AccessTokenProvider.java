package com.example.demo.push.service;

import java.io.IOException;

// 구글에 보낼 때 쓰는 "출입증(액세스 토큰)"을 건네주는 작은 부품 (S24-b).
// 진짜는 서비스 계정 키로 구글에서 받아 오고(만료 전에 라이브러리가 새로 받음), 테스트는 가짜 값을 돌려줘서 구글을 안 부른다.
public interface AccessTokenProvider {

    // 못 받으면 IOException
    String accessToken() throws IOException;
}
