package com.example.demo.permit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 허가서 신청 하루 횟수 제한(S20-a): 회원당 하루 3회. 넘으면 429.
// 행 수가 아니라 Redis로 센다: 신청을 철회(행 삭제)하고 다시 내는 식으로 제한을 빠져나가지 못하게.
// 키 "permit-upload-day:{회원id}:{서울 날짜}" (챗봇 하루 제한과 같은 방식: 서울 날짜 기준 고정 창, 자정이 지나면 새 키라 0부터).
// 날짜는 주입된 Clock(서울)으로 계산한다. TTL 25시간: 자정이 지나면 키 이름이 바뀌므로 여유만 두면 된다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PermitRateLimiter {

    static final int DAILY_LIMIT = 3;

    private static final String DAY_PREFIX = "permit-upload-day:";
    private static final long DAY_TTL_SECONDS = Duration.ofHours(25).toSeconds();

    // 증가(INCR)와 첫 증가 때 TTL 걸기를 한 번에(Lua). 따로 하면 그 사이에 서버가 죽을 때 TTL 없는 키가 남아 영영 막힐 수 있다.
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    // 되돌리기(DECR): 키가 있고 0보다 클 때만 1 줄인다
    private static final DefaultRedisScript<Long> REFUND_SCRIPT = new DefaultRedisScript<>(
            "local c = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "if c > 0 then return redis.call('DECR', KEYS[1]) end "
                    + "return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    // 서버 탓으로 실패한 신청(처리 자리 없음 503, 창고 저장 실패 500, 동시 신청에서 진 409 등)의 카운트를 1 되돌린다.
    // 되돌리기에 실패해도(Redis 장애) 원래 오류를 가리지 않도록 예외를 던지지 않는다. 하루 횟수 1회를 잃을 뿐이다.
    public void refund(Long userId) {
        try {
            redis.execute(REFUND_SCRIPT, List.of(DAY_PREFIX + userId + ":" + LocalDate.now(clock)));
        } catch (RuntimeException e) {
            log.warn("허가서 신청 횟수 되돌리기에 실패했습니다: userId={}", userId);
        }
    }

    // 신청 1회를 센다. 오늘 4번째부터는 429(카운터는 계속 올라가도 무방하다: 하루 지나면 키가 사라진다)
    public void countOrReject(Long userId) {
        Long count = redis.execute(INCR_SCRIPT,
                List.of(DAY_PREFIX + userId + ":" + LocalDate.now(clock)),
                String.valueOf(DAY_TTL_SECONDS));
        if (count == null) {
            throw new IllegalStateException("Redis 스크립트 결과가 없습니다.");
        }
        if (count > DAILY_LIMIT) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "하루 신청 횟수(3회)를 모두 사용했습니다. 내일 다시 시도해 주세요.");
        }
    }
}
