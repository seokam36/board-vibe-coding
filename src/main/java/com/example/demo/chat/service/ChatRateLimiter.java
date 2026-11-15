package com.example.demo.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.config.ChatProperties;

import lombok.RequiredArgsConstructor;

// 회원별 챗봇 질문 횟수 제한(S17-f1): 분당 N회 + 하루 M회(기본 5 / 30). 넘으면 429.
// 키 2개 (Redis, "고정 창" 방식: 창이 바뀌면 새 키라 0부터 다시 센다):
//  - "chat-rate-min:{회원id}:{분 번호}"      분 번호 = 시계의 epoch 분(초 / 60). TTL 2분
//  - "chat-rate-day:{회원id}:{서울 날짜}"    날짜 = yyyy-MM-dd(서울). TTL 25시간(자정이 지나면 키 이름이 바뀌므로 여유만 두면 된다)
// 분 번호·날짜는 주입된 Clock으로 계산한다(테스트가 가짜 시계로 시간을 건너뛴다). Redis 시간(TTL)과는 무관하다.
// 고정 창의 한계: 분 경계에 걸치면 짧은 시간에 최대 2배(예: 59초에 5번 + 다음 분 0초에 5번)까지 통과할 수 있다. 서버를 지키는 용도라 받아들인다.
@Component
@RequiredArgsConstructor
public class ChatRateLimiter {

    // 응답 본문에는 나가지 않는다(spring.web.error.include-message 기본값 never, Boot 4 이름). 화면은 상태코드(429)로 문구를 정한다.
    static final String LIMIT_MESSAGE = "챗봇 질문 횟수를 모두 사용했습니다. 잠시 뒤에 다시 시도해 주세요.";

    private static final String MINUTE_PREFIX = "chat-rate-min:";
    private static final String DAY_PREFIX = "chat-rate-day:";
    private static final long MINUTE_TTL_SECONDS = Duration.ofMinutes(2).toSeconds();
    private static final long DAY_TTL_SECONDS = Duration.ofHours(25).toSeconds();

    // 📦 StringRedisTemplate: Spring이 준 Redis 호출 도구. 문자열 키·값을 다룬다.
    private final StringRedisTemplate redis;
    private final ChatProperties props;
    private final Clock clock;

    // 검사 + 증가를 Lua 한 번에 실행한다(📦 Lua: Redis가 스크립트를 끊지 않고 통째로 돌려서, 동시에 요청이 와도 한도를 못 넘는다).
    // 분 한도를 먼저, 그다음 하루 한도를 본다. 하나라도 가득 차 있으면 아무 카운터도 늘리지 않고 1(분) 또는 2(하루)를 돌려준다.
    // 둘 다 한도 안이면 둘 다 +1(첫 증가 때 TTL을 건다) 하고 0.
    // KEYS: [1]=분 카운터, [2]=하루 카운터 / ARGV: [1]=분 상한, [2]=하루 상한, [3]=분 TTL(초), [4]=하루 TTL(초)
    private static final DefaultRedisScript<Long> COUNT_SCRIPT = new DefaultRedisScript<>(
            "local m = tonumber(redis.call('GET', KEYS[1]) or '0') "
                    + "if m >= tonumber(ARGV[1]) then return 1 end "
                    + "local d = tonumber(redis.call('GET', KEYS[2]) or '0') "
                    + "if d >= tonumber(ARGV[2]) then return 2 end "
                    + "if redis.call('INCR', KEYS[1]) == 1 then redis.call('EXPIRE', KEYS[1], ARGV[3]) end "
                    + "if redis.call('INCR', KEYS[2]) == 1 then redis.call('EXPIRE', KEYS[2], ARGV[4]) end "
                    + "return 0",
            Long.class);

    // 질문 1회를 센다. 한도를 넘었으면 429를 던지고(이때는 카운터를 늘리지 않는다), 아니면 그냥 돌아온다.
    public void countOrReject(Long userId) {
        long minute = clock.instant().getEpochSecond() / 60;
        LocalDate today = LocalDate.now(clock);
        Long result = redis.execute(COUNT_SCRIPT,
                List.of(MINUTE_PREFIX + userId + ":" + minute, DAY_PREFIX + userId + ":" + today),
                String.valueOf(props.rateLimit().perMinute()),
                String.valueOf(props.rateLimit().perDay()),
                String.valueOf(MINUTE_TTL_SECONDS),
                String.valueOf(DAY_TTL_SECONDS));
        if (result == null) {
            throw new IllegalStateException("Redis 스크립트 결과가 없습니다.");
        }
        if (result != 0L) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, LIMIT_MESSAGE);
        }
    }
}
