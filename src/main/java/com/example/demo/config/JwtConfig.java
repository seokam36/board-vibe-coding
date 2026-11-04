package com.example.demo.config;

import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

// JWT를 만들고(JwtEncoder) 검증하는(JwtDecoder) 도구를 같은 비밀키로 등록한다.
// HS256: 한 개의 비밀키로 서명하고 검증하는 방식 (서버 하나라 충분하다).
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    private static final int MIN_KEY_BYTES = 32; // HS256 권장 최소 길이(256비트)

    // 비밀키를 읽어 검증한다. 잘못되면 서버 시작 단계에서 이유를 알려 주며 실패한다.
    @Bean
    SecretKey jwtSecretKey(JwtProperties props) {
        String secret = props.secret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET이 비어 있습니다. Base64 문자열로 설정하세요 (.env.example 참고).");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("JWT_SECRET이 올바른 Base64 문자열이 아닙니다.", e);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET은 디코딩 후 " + MIN_KEY_BYTES + "바이트 이상이어야 합니다 (현재 " + bytes.length + "바이트).");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSecretKey).build();
    }

    // 서명·만료를 검사한다. 알고리즘을 HS256으로 고정해서 다른 알고리즘으로 위조한 토큰을 막는다.
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
    }
}
