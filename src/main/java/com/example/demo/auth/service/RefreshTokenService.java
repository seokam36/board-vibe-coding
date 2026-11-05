package com.example.demo.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.example.demo.config.JwtProperties;

import lombok.RequiredArgsConstructor;

// 리프레시 토큰의 발급·회전·삭제를 Redis로 처리한다.
// 토큰은 의미 없는 랜덤 문자열이고, Redis에는 원문이 아니라 SHA-256 해시를 키로 저장한다.
// (Redis가 털려도 쿠키에 쓸 수 있는 원문 토큰은 알 수 없게 하기 위함)
// 키: "refresh:" + 해시(hex), 값: 사용자 id, TTL: 리프레시 토큰 수명(7일)
// 보조 목록: "user-refresh:" + 사용자 id (Set), 원소: 그 회원의 토큰 해시(hex). 회원 단위로 전부 끊기(revokeAll) 위한 색인이다.
//   Set 키 TTL도 7일로 갱신한다(마지막 발급 기준). 이미 만료된 refresh 키의 해시가 Set에 남을 수 있지만 지울 때 없는 키는 무시하므로 문제없다.
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "refresh:";
    private static final String USER_KEY_PREFIX = "user-refresh:";
    private static final int TOKEN_BYTES = 32;

    private final StringRedisTemplate redis;
    private final JwtProperties props;
    private final SecureRandom secureRandom = new SecureRandom();

    // 발급: refresh 키 저장 + 회원 Set에 해시 추가 + Set TTL 갱신을 Lua 스크립트 한 번에 실행한다.
    // Redis는 스크립트를 중간에 끊지 않고 통째로 실행하므로 "키만 생기고 Set에는 없는" 틈이 생기지 않는다.
    // Set TTL은 늘어날 때만 갱신한다(TTL이 없거나 키가 없으면 음수라 함께 처리됨).
    // 이유: 설정에서 리프레시 수명을 줄인 뒤 발급해도 Set TTL이 줄지 않아, 수명이 더 긴 옛 토큰보다 Set이 먼저 사라지는 일을 막는다.
    // (EXPIRE ... GT는 TTL이 없는 새 Set에는 적용되지 않아 쓰지 않는다)
    // Redis Cluster로 옮기면 한 스크립트가 여러 키를 건드리는 CROSSSLOT 문제가 생겨 키에 {userId} 해시태그를 넣는 설계 변경이 필요하다.
    // KEYS[1]=refresh 키, KEYS[2]=회원 Set 키, ARGV[1]=userId, ARGV[2]=TTL(초), ARGV[3]=해시
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) "
                    + "redis.call('SADD', KEYS[2], ARGV[3]) "
                    + "local cur = redis.call('TTL', KEYS[2]) "
                    + "if cur < tonumber(ARGV[2]) then redis.call('EXPIRE', KEYS[2], ARGV[2]) end "
                    + "return 1",
            Long.class);

    // 전부 끊기: Set의 해시마다 refresh 키를 지우고 Set도 지운다(없는 키 DEL은 0을 돌려줄 뿐 오류 아님). 이것도 한 번에 실행.
    // KEYS[1]=회원 Set 키, ARGV[1]=refresh 키 접두사
    private static final DefaultRedisScript<Long> REVOKE_ALL_SCRIPT = new DefaultRedisScript<>(
            "local hashes = redis.call('SMEMBERS', KEYS[1]) "
                    + "for _, h in ipairs(hashes) do redis.call('DEL', ARGV[1] .. h) end "
                    + "redis.call('DEL', KEYS[1]) "
                    + "return #hashes",
            Long.class);

    // 새 토큰을 만들어 Redis에 저장하고 원문을 돌려준다(쿠키에 담을 값). 로그인마다 별도 키라 여러 기기 동시 로그인이 된다.
    public String issue(Long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String hash = hash(token);
        redis.execute(ISSUE_SCRIPT,
                List.of(KEY_PREFIX + hash, userKey(userId)),
                String.valueOf(userId),
                String.valueOf(props.refreshTokenExpiry().toSeconds()),
                hash);
        return token;
    }

    // 재발급용: 키를 꺼내면서 동시에 지운다(GETDEL, 원자적). 그래서 같은 토큰을 두 번 쓰면 두 번째는 비어 있다.
    // 없음/모름/이미 사용/만료는 모두 빈 값 하나로 합쳐 이유를 구분하지 않는다.
    public Optional<Long> consume(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        String hash = hash(token);
        String value = redis.opsForValue().getAndDelete(KEY_PREFIX + hash);
        if (value == null) {
            return Optional.empty();
        }
        try {
            Long userId = Long.parseLong(value);
            // 옛 해시를 회원 목록에서 뺀다(새 해시는 이어서 issue가 넣는다 → 개수 유지).
            // GETDEL과 SREM은 두 명령이라 사이에 아주 짧은 틈이 있지만, 틈에 revokeAll이 끼면 Set만 먼저 지워질 뿐 키는 이미 없어서 안전하다.
            redis.opsForSet().remove(userKey(userId), hash);
            return Optional.of(userId);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    // 로그아웃용: 토큰이 있으면 Redis에서 지우고 회원 목록에서도 뺀다. 없어도 조용히 넘어간다.
    // 값을 지우면서 받아(GETDEL) userId를 알아내 그 회원의 Set에서 해시를 뺀다.
    public void revoke(String token) {
        if (token == null || token.isEmpty()) {
            return;
        }
        String hash = hash(token);
        String value = redis.opsForValue().getAndDelete(KEY_PREFIX + hash);
        if (value == null) {
            return; // 이미 없음(만료/이미 로그아웃). Set에 남은 해시는 만료 때 같이 사라지거나 revokeAll이 무시한다.
        }
        try {
            redis.opsForSet().remove(userKey(Long.parseLong(value)), hash);
        } catch (NumberFormatException e) {
            // 값이 숫자가 아니면 Set 정리는 건너뛴다(키는 이미 지워짐)
        }
    }

    // 이 회원의 모든 리프레시 토큰을 한꺼번에 무효화한다(모든 기기 로그아웃). 비밀번호 변경·탈퇴에서 쓸 예정.
    // 알려진 틈: 이 호출과 동시에 같은 회원의 재발급이 일어나면 새 토큰 하나가 살아남을 수 있다(허용).
    public void revokeAll(Long userId) {
        redis.execute(REVOKE_ALL_SCRIPT, List.of(userKey(userId)), KEY_PREFIX);
    }

    private String userKey(Long userId) {
        return USER_KEY_PREFIX + userId;
    }

    private String hash(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 지원하므로 실제로는 일어나지 않는다
            throw new IllegalStateException(e);
        }
    }
}
