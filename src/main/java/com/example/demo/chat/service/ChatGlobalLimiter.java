package com.example.demo.chat.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.config.ChatProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 서버 전체 챗봇 질문 상한(S18-d): 모든 회원을 합쳐 분당 N질문 + 하루 M질문. 넘으면 503.
// 왜: 구글 무료 한도(분당 15·하루 500 호출)를 다 쓴 뒤에도 구글을 계속 부르는 헛호출을 막고, "양식은 됐는데 답에서 429"도 줄인다.
// 질문 하나 = AI 호출 최대 2번이라 "호출"이 아니라 "질문"으로 센다(운영 권장: 분 7 = 15÷2, 하루 200 = 500×0.8÷2).
// 키 2개 (Redis, 회원별 제한과 같은 "고정 창" 방식):
//  - "chat-global-min:{분 번호}"            분 번호 = 시계의 epoch 분. TTL 2분
//  - "chat-global-day:{태평양 날짜}"         날짜 = yyyy-MM-dd(America/Los_Angeles, 구글 하루 한도가 리셋되는 기준). TTL 26시간(서머타임 끝나는 날은 하루가 25시간이라 넉넉히)
// 0이면 그 칸은 검사하지 않는다(기본 0 = 로컬 ollama 개발에 영향 없음). 둘 다 0이면 Redis도 안 부른다.
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatGlobalLimiter {

    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    private static final String MINUTE_PREFIX = "chat-global-min:";
    private static final String DAY_PREFIX = "chat-global-day:";
    private static final long MINUTE_TTL_SECONDS = Duration.ofMinutes(2).toSeconds();
    private static final long DAY_TTL_SECONDS = Duration.ofHours(26).toSeconds();

    private final StringRedisTemplate redis;
    private final ChatProperties props;
    private final Clock clock;

    // 질문 1개를 센다. 상한을 넘었으면 503을 던지고(이때는 전체 카운터를 늘리지 않는다), 아니면 그냥 돌아온다.
    public void countOrReject() {
        int perMinute = props.globalLimit().perMinute();
        int perDay = props.globalLimit().perDay();
        if (perMinute == 0 && perDay == 0) {
            return; // 둘 다 꺼짐: Redis 키를 만들지 않는다
        }
        Instant now = clock.instant(); // 분 키와 하루 키가 같은 시각으로 만들어지게 한 번만 읽는다
        // 회원별 제한의 Lua 스크립트를 그대로 쓴다(분·하루 두 카운터를 한 번에 검사, 통과 때만 +1, 원자적).
        // 한 칸만 0(끔)이면 그 칸의 상한을 int 최댓값으로 줘서 사실상 검사하지 않는다(Lua 숫자는 double이라 이 값은 정확히 표현된다).
        Long result = redis.execute(ChatRateLimiter.COUNT_SCRIPT,
                List.of(minuteKey(now), dayKey(now)),
                String.valueOf(perMinute == 0 ? Integer.MAX_VALUE : perMinute),
                String.valueOf(perDay == 0 ? Integer.MAX_VALUE : perDay),
                String.valueOf(MINUTE_TTL_SECONDS),
                String.valueOf(DAY_TTL_SECONDS));
        if (result == null) {
            throw new IllegalStateException("Redis 스크립트 결과가 없습니다."); // 회원별 제한과 같은 동작(500)
        }
        if (result != 0L) {
            // 구글 429 "한도 초과"·자리 없음 503·예산 소진 504 로그와 구분되는 문구. 회원 id·질문은 남기지 않는다.
            log.warn("챗봇 서버 전체 상한({}) 넘음 -> 503", result == 1L ? "분" : "하루");
            // 응답 본문에는 나가지 않는다(화면은 상태코드 503으로 문구를 정한다). 원인이 드러나지 않게 다른 503과 같은 고정 문구를 쓴다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ChatService.UNAVAILABLE_MESSAGE);
        }
    }

    // 분 카운터 키: 분 번호 = epoch 초 / 60
    static String minuteKey(Instant now) {
        return MINUTE_PREFIX + now.getEpochSecond() / 60;
    }

    // 하루 카운터 키: 태평양 날짜(서머타임이 알아서 반영된다)
    static String dayKey(Instant now) {
        return DAY_PREFIX + LocalDate.ofInstant(now, PACIFIC);
    }
}
