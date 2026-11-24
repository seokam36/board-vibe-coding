package com.example.demo.market.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.example.demo.config.MarketSafeSearchProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 중고 사진 검사 전용 구글 하루 상한 (S30-a). PermitVisionDailyLimiter와 같은 방식이지만 키가 따로다.
// 왜 따로: 허가서 판독과 한 카운터를 쓰면 사진 검사가 허가서 몫까지 먹어 버리고, 서로 무료 한도를 갉아먹는다.
// 키 "market-vision-day:{서울 날짜}" (Lua로 원자적 증가, 자정이 지나면 새 키, TTL 2일). 허가서 키는 건드리지 않는다.
// 넘거나 Redis가 고장 나면 false(구글을 부르지 말 것) -> 호출한 쪽이 UNCHECKED로 처리한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketSafeSearchDailyLimiter {

    private static final String DAY_PREFIX = "market-vision-day:";
    private static final long DAY_TTL_SECONDS = Duration.ofDays(2).toSeconds();

    // 증가와 첫 증가 때 TTL 걸기를 한 번에(Lua). 따로 하면 그 사이 서버가 죽을 때 TTL 없는 키가 남는다.
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final MarketSafeSearchProperties props;
    private final Clock clock;

    // 구글 호출 1회를 센다. 오늘 상한 안이면 true, 넘었거나 Redis 오류면 false.
    public boolean tryAcquire() {
        int limit = props.perDay();
        if (limit == 0) {
            return true; // 상한 꺼짐: 세지도 Redis를 건드리지도 않는다
        }
        try {
            Long count = redis.execute(INCR_SCRIPT, List.of(DAY_PREFIX + LocalDate.now(clock)),
                    String.valueOf(DAY_TTL_SECONDS));
            if (count == null) {
                throw new IllegalStateException("Redis 스크립트 결과가 없습니다.");
            }
            if (count > limit) {
                // 처음 넘는 한 번만 남겨 로그 폭주를 막는다
                if (count == limit + 1L) {
                    log.warn("사진 검사 하루 상한({}건)에 도달했습니다: 오늘 남은 사진은 검사 없이 통과합니다.", limit);
                }
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // 요금 안전 쪽: 세지 못하면 구글을 부르지 않는다. 예외 이름만 남긴다.
            log.warn("사진 검사 하루 상한 확인 실패(Redis): 검사 없이 통과합니다. error={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
