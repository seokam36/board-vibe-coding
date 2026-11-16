package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// application.properties의 app.permit.* 설정 (S20-b).
// hashSecret: 허가서 번호 해시(HMAC-SHA256)에 쓰는 비밀 값(환경변수 PERMIT_HASH_SECRET). 비었거나 짧으면 PermitNumberHash가 시작 때 막는다.
@ConfigurationProperties("app.permit")
public record PermitProperties(@DefaultValue("") String hashSecret) {

    // record 기본 toString은 비밀 값을 그대로 찍으므로 가린다
    @Override
    public String toString() {
        return "PermitProperties[hashSecret=" + (hashSecret == null || hashSecret.isEmpty() ? "(없음)" : "****") + "]";
    }
}
