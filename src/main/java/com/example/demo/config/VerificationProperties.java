package com.example.demo.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// application.properties의 app.verification.* 설정 (학교 메일 인증. 비밀 값 아님)
// codeTtl: 코드 유효 시간 / resendInterval: 재발송 최소 간격 / dailyLimit: 회원당 하루 발송 상한 / maxAttempts: 확인 실패 허용 횟수(S10-c에서 사용)
@ConfigurationProperties("app.verification")
public record VerificationProperties(
        @DefaultValue("10m") Duration codeTtl,
        @DefaultValue("60s") Duration resendInterval,
        @DefaultValue("10") int dailyLimit,
        @DefaultValue("5") int maxAttempts) {
}
