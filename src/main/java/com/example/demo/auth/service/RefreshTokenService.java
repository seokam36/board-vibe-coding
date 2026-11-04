package com.example.demo.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.example.demo.config.JwtProperties;

import lombok.RequiredArgsConstructor;

// 리프레시 토큰의 발급·회전·삭제를 Redis로 처리한다.
// 토큰은 의미 없는 랜덤 문자열이고, Redis에는 원문이 아니라 SHA-256 해시를 키로 저장한다.
// (Redis가 털려도 쿠키에 쓸 수 있는 원문 토큰은 알 수 없게 하기 위함)
// 키: "refresh:" + 해시(hex), 값: 사용자 id, TTL: 리프레시 토큰 수명(7일)
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final JwtProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    // 새 토큰을 만들어 Redis에 저장하고 원문을 돌려준다(쿠키에 담을 값). 로그인마다 별도 키라 여러 기기 동시 로그인이 된다.
    public String issue(Long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redis.opsForValue().set(key(token), String.valueOf(userId), props.refreshTokenExpiry());
        return token;
    }

    // 재발급용: 키를 꺼내면서 동시에 지운다(GETDEL, 원자적). 그래서 같은 토큰을 두 번 쓰면 두 번째는 비어 있다.
    // 없음/모름/이미 사용/만료는 모두 빈 값 하나로 합쳐 이유를 구분하지 않는다.
    public Optional<Long> consume(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        String value = redis.opsForValue().getAndDelete(key(token));
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // 로그아웃용: 토큰이 있으면 Redis에서 지운다. 없어도 조용히 넘어간다.
    public void revoke(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        redis.delete(key(token));
    }

    private String key(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 지원하므로 실제로는 일어나지 않는다
            throw new IllegalStateException(e);
        }
    }
}
