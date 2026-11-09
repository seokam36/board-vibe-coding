package com.example.demo.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// "오늘"을 서울 기준으로 알려 주는 시계. 서비스가 LocalDate.now() 대신 이 시계를 받아 쓰면
// 테스트에서 시계를 가짜(Clock.fixed)로 바꿔 끼워 날짜를 고정할 수 있다.
@Configuration
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
