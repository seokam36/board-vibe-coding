package com.example.demo.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// @Scheduled 메서드(사진 청소부 등)를 실제로 돌리는 스위치.
// app.scheduling.enabled=false 로 끄면 @Scheduled가 무시된다 (테스트 설정에서 꺼서, 테스트 도중 저절로 청소가 돌지 않게 한다).
// 설정이 없으면 켜진다(운영 기본).
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
