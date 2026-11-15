package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// application.properties의 app.chat.* 설정 (챗봇. 비밀 값 아님)
// rateLimit: 회원 한 명이 챗봇에 질문할 수 있는 횟수 상한(S17-f1). perMinute = 1분에 몇 번 / perDay = 서울 하루(자정~자정)에 몇 번
@ConfigurationProperties("app.chat")
public record ChatProperties(@DefaultValue RateLimit rateLimit) {

    public record RateLimit(
            @DefaultValue("5") int perMinute,
            @DefaultValue("30") int perDay) {
    }
}
