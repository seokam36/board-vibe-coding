package com.example.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// application.properties의 app.chat.* 설정 (챗봇. 비밀 값 아님)
// (기본값 5·30을 바꾸면 flutter_app/lib/errors/error_messages.dart의 chatRateLimited 문구와 docs/api.md 43번도 같이 바꾼다)
// rateLimit: 회원 한 명이 챗봇에 질문할 수 있는 횟수 상한(S17-f1). perMinute = 1분에 몇 번 / perDay = 서울 하루(자정~자정)에 몇 번
// maxConcurrent: 서버 전체에서 동시에 진행할 수 있는 질문 수(S17-f2). slotWait: 자리가 없을 때 기다리는 최대 시간(넘으면 503)
// globalLimit: 서버 전체(모든 회원 합계) 질문 수 상한(S18-d). 구글 무료 한도를 다 쓴 뒤의 헛호출을 막는다. 0이면 그 칸은 검사하지 않는다(기본 0)
@ConfigurationProperties("app.chat")
public record ChatProperties(
        @DefaultValue RateLimit rateLimit,
        @DefaultValue GlobalLimit globalLimit,
        @DefaultValue("2") int maxConcurrent,
        @DefaultValue("5s") Duration slotWait) {

    public record RateLimit(
            @DefaultValue("5") int perMinute,
            @DefaultValue("30") int perDay) {
    }

    // 단위는 "질문"이다(AI 호출이 아님. 질문 하나 = AI 호출 최대 2번). perDay는 태평양 시간 하루(구글 무료 한도 리셋 기준)
    public record GlobalLimit(
            @DefaultValue("0") int perMinute,
            @DefaultValue("0") int perDay) {
    }
}
