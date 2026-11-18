package com.example.demo.permit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.example.demo.config.PermitOcrProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 구글 Vision 하루 전체 상한(S21-d): 모든 회원을 합쳐 하루 N번까지만 구글을 부른다(서울 날짜).
// 왜: 회원당 하루 3회 제한만으로는 계정을 여러 개 만들면 Vision 월 무료 1,000건을 넘길 수 있다(요금 보호).
// 키 "permit-vision-day:{서울 날짜}" (PermitRateLimiter와 같은 Lua 원자적 증가, 자정이 지나면 새 키라 0부터). TTL 2일: 쌓이지 않게만.
// 상한 0이면 검사도 Redis 키도 없다. PERMIT_OCR_MODE=off면 구글을 안 부르므로 세지 않는다.
// 상한을 넘거나 Redis가 고장 나면 false(구글을 부르지 말 것). 호출한 쪽이 "못 읽음 = 관리자 대기"로 처리한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class PermitVisionDailyLimiter {

    private static final String DAY_PREFIX = "permit-vision-day:";
    private static final long DAY_TTL_SECONDS = Duration.ofDays(2).toSeconds();

    // 증가(INCR)와 첫 증가 때 TTL 걸기를 한 번에(Lua). 따로 하면 그 사이 서버가 죽을 때 TTL 없는 키가 남는다.
    private static final DefaultRedisScript<Long> INCR_SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end "
                    + "return c",
            Long.class);

    private final StringRedisTemplate redis;
    private final PermitOcrProperties props;
    private final Clock clock;

    // 구글 호출 1회를 센다. 오늘 상한 안이면 true(구글을 불러도 됨), 넘었거나 Redis 오류면 false(부르면 안 됨).
    public boolean tryAcquire() {
        int limit = props.vision().globalPerDay();
        if (limit == 0 || "off".equals(props.mode())) {
            return true; // 상한 꺼짐이거나 off 모드(구글 안 부름): 세지도 Redis를 건드리지도 않는다. 모르는 모드는 요금 보호상 센다
        }
        try {
            Long count = redis.execute(INCR_SCRIPT, List.of(DAY_PREFIX + LocalDate.now(clock)),
                    String.valueOf(DAY_TTL_SECONDS));
            if (count == null) {
                throw new IllegalStateException("Redis 스크립트 결과가 없습니다.");
            }
            if (count > limit) {
                // 처음 넘는 한 번만 남겨 로그 폭주를 막는다(이후 신청은 조용히 관리자 대기). 회원·사진 정보는 넣지 않는다.
                if (count == limit + 1L) {
                    log.warn("Vision 하루 전체 상한({}건)에 도달했습니다: 오늘 남은 신청은 판독 없이 관리자 대기로 둡니다.", limit);
                }
                return false;
            }
            return true;
        } catch (RuntimeException e) {
            // 요금 안전 쪽: 세지 못하면 구글을 부르지 않는다. 예외 이름만 남긴다.
            log.warn("Vision 하루 상한 확인 실패(Redis): 판독 없이 관리자 대기로 둡니다. error={}", e.getClass().getSimpleName());
            return false;
        }
    }
}
