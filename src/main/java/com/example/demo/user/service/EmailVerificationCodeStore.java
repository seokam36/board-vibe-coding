package com.example.demo.user.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.example.demo.config.VerificationProperties;

import lombok.RequiredArgsConstructor;

// 학교 메일 인증 코드를 Redis에 저장한다. 회원 한 명당 코드는 항상 하나뿐이다.
// 키 3개 (userId = 회원 id):
//  - "email-verify:{userId}"          (Hash) codeHash, email(정규화 메일 원문, S10-c의 해시 계산용), schoolId, attempts=0. TTL = 코드 수명(10분)
//  - "email-verify-cooldown:{userId}" 재발송 제한용. TTL = 재발송 간격(60초). 있으면 아직 못 보냄
//  - "email-verify-daily:{userId}"    하루 발송 횟수(INCR). 첫 발송 때 TTL 24시간을 건다
// 평문 코드는 어디에도 저장하지 않는다(해시만). 메일 원문은 10분 TTL 동안만 Redis에 있고 DB에는 저장하지 않는다.
// Redis Cluster로 옮기면 한 스크립트가 여러 키를 건드리는 CROSSSLOT 문제가 생겨 {userId} 해시태그가 필요하다(RefreshTokenService와 같은 한계).
@Component
@RequiredArgsConstructor
public class EmailVerificationCodeStore {

    public enum ReserveResult { OK, COOLDOWN, DAILY_LIMIT }

    private static final String CODE_PREFIX = "email-verify:";
    private static final String COOLDOWN_PREFIX = "email-verify-cooldown:";
    private static final String DAILY_PREFIX = "email-verify-daily:";
    private static final long DAILY_TTL_SECONDS = Duration.ofHours(24).toSeconds();

    private final StringRedisTemplate redis;
    private final VerificationProperties props;

    // 검사 + 저장을 Lua 한 번에 실행한다. Redis가 스크립트를 끊지 않고 통째로 돌리므로
    // 동시에 두 요청이 와도 쿨다운·하루 상한 검사를 한 요청만 통과한다.
    // 순서: 쿨다운 중이면 1, 하루 상한이면 2 (둘 다 아무것도 바꾸지 않음) -> 통과하면 쿨다운 설정, 횟수 +1, 코드 Hash 덮어쓰기(옛 코드 무효), 0
    // KEYS: [1]=코드, [2]=쿨다운, [3]=하루 횟수
    // ARGV: [1]=codeHash, [2]=email, [3]=schoolId, [4]=코드 TTL(초), [5]=쿨다운 TTL(초), [6]=하루 상한, [7]=하루 TTL(초)
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[2]) == 1 then return 1 end "
                    + "local cnt = tonumber(redis.call('GET', KEYS[3]) or '0') "
                    + "if cnt >= tonumber(ARGV[6]) then return 2 end "
                    + "redis.call('SET', KEYS[2], '1', 'EX', ARGV[5]) "
                    + "if redis.call('INCR', KEYS[3]) == 1 then redis.call('EXPIRE', KEYS[3], ARGV[7]) end "
                    + "redis.call('DEL', KEYS[1]) "
                    + "redis.call('HSET', KEYS[1], 'codeHash', ARGV[1], 'email', ARGV[2], 'schoolId', ARGV[3], 'attempts', '0') "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[4]) "
                    + "return 0",
            Long.class);

    // 발송 실패 되돌리기: 코드·쿨다운을 지우고 하루 횟수를 1 되돌린다(0 이하가 되면 키 삭제). 바로 다시 시도할 수 있게 한다.
    // "내가 저장한 코드"일 때만 되돌린다(저장된 codeHash == 내 codeHash). 발송이 오래 걸려 쿨다운이 끝난 사이
    // 다른 요청이 새 코드를 저장했다면 그 코드·쿨다운·횟수를 건드리지 않는다. 안 지웠으면 0, 되돌렸으면 1.
    // KEYS: [1]=코드, [2]=쿨다운, [3]=하루 횟수 / ARGV: [1]=내 codeHash
    private static final DefaultRedisScript<Long> ROLLBACK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('HGET', KEYS[1], 'codeHash') ~= ARGV[1] then return 0 end "
                    + "redis.call('DEL', KEYS[1], KEYS[2]) "
                    + "if redis.call('EXISTS', KEYS[3]) == 1 then "
                    + "if redis.call('DECR', KEYS[3]) <= 0 then redis.call('DEL', KEYS[3]) end end "
                    + "return 1",
            Long.class);

    // 제한을 통과하면 코드를 저장하고 OK. 아니면 이유만 돌려주고 기존 코드는 그대로 둔다.
    public ReserveResult reserve(Long userId, String code, String normalizedEmail, Long schoolId) {
        Long result = redis.execute(RESERVE_SCRIPT,
                keys(userId),
                codeHash(userId, code),
                normalizedEmail,
                String.valueOf(schoolId),
                String.valueOf(props.codeTtl().toSeconds()),
                String.valueOf(props.resendInterval().toSeconds()),
                String.valueOf(props.dailyLimit()),
                String.valueOf(DAILY_TTL_SECONDS));
        if (result == null) {
            throw new IllegalStateException("Redis 스크립트 결과가 없습니다.");
        }
        return switch (result.intValue()) {
            case 0 -> ReserveResult.OK;
            case 1 -> ReserveResult.COOLDOWN;
            default -> ReserveResult.DAILY_LIMIT;
        };
    }

    // 발송이 실패했을 때 reserve로 저장한 것을 되돌린다(같은 code로 저장한 것일 때만)
    public void rollback(Long userId, String code) {
        redis.execute(ROLLBACK_SCRIPT, keys(userId), codeHash(userId, code));
    }

    private List<String> keys(Long userId) {
        return List.of(CODE_PREFIX + userId, COOLDOWN_PREFIX + userId, DAILY_PREFIX + userId);
    }

    // 코드 해시: 회원 id를 섞어 SHA-256(hex). 평문 코드는 저장하지 않는다. (S10-c의 확인도 같은 방식으로 비교해야 한다)
    // 6자리는 경우의 수가 100만뿐이라 해시만으로는 Redis가 털리면 풀린다 -> 5번 실패 폐기·10분 TTL이 실제 방어선이다.
    static String codeHash(Long userId, String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest((userId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
