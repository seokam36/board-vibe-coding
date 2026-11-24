package com.example.demo.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.GenericContainer;

import com.example.demo.config.MarketSafeSearchProperties;
import com.example.demo.market.service.ImageSafetyJudge.Rule;
import com.example.demo.market.domain.ImageSafetyResult;
import com.example.demo.market.domain.SafeSearchLikelihood;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.domain.SafetyVerdict;
import com.sun.net.httpserver.HttpServer;

// S30-a: 사진 검사 하루 상한. 진짜 Redis(Testcontainers)로 "31번째는 구글을 안 부르고, 허가서 카운터는 그대로"를 보고,
// 가짜 Redis(Mockito)로 오류·0·날짜 경계를 본다. 스프링 컨텍스트는 띄우지 않는다.
@SuppressWarnings({ "unchecked", "rawtypes" })
class MarketSafeSearchDailyLimiterTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final String MARKET_KEY = "market-vision-day:2026-11-18";
    private static final String PERMIT_KEY = "permit-vision-day:2026-11-18";

    private static GenericContainer<?> redisContainer;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        redisContainer = new GenericContainer<>("redis:8.10.3-alpine").withExposedPorts(6379);
        redisContainer.start();
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(redisContainer.getHost(), redisContainer.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (factory != null) factory.destroy();
        if (redisContainer != null) redisContainer.stop();
    }

    private static MarketSafeSearchProperties props(int perDay) {
        MarketSafeSearchProperties.Rules off = new MarketSafeSearchProperties.Rules("OFF", "OFF");
        return new MarketSafeSearchProperties("vision", "k", "http://127.0.0.1:1", Duration.ofSeconds(5), 2, DataSize.ofMegabytes(7),
                perDay, off, off, off);
    }

    // ---- 완료 조건 6: 진짜 Redis ----

    @Test
    void 하루_상한_30에서_31번째는_구글을_안_부르고_UNCHECKED이며_허가서_카운터는_그대로이다() throws IOException {
        redis.delete(List.of(MARKET_KEY, PERMIT_KEY));
        redis.opsForValue().set(PERMIT_KEY, "7"); // 허가서 쪽 카운터는 건드리면 안 된다

        // 가짜 구글 서버: 호출 수를 센다
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            ex.getRequestBody().readAllBytes();
            byte[] b = "{\"responses\":[{\"safeSearchAnnotation\":{\"adult\":\"VERY_UNLIKELY\",\"violence\":\"VERY_UNLIKELY\",\"racy\":\"VERY_UNLIKELY\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();

        MarketSafeSearchDailyLimiter limiter = new MarketSafeSearchDailyLimiter(redis, props(30), CLOCK);
        Map<SafetyCategory, Rule> rules = new EnumMap<>(SafetyCategory.class);
        rules.put(SafetyCategory.ADULT, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.POSSIBLE));
        try (VisionImageSafetyChecker checker = new VisionImageSafetyChecker("http://127.0.0.1:" + server.getAddress().getPort(), "KEY-1",
                Duration.ofSeconds(5), 2, 1024 * 1024, new ImageSafetyJudge(rules), limiter::tryAcquire)) {
            byte[] image = "img".getBytes(StandardCharsets.UTF_8);
            for (int i = 1; i <= 30; i++) {
                assertThat(checker.check(image).verdict()).as("%d번째", i).isEqualTo(SafetyVerdict.PASS);
            }
            assertThat(hits.get()).isEqualTo(30);

            ImageSafetyResult thirtyFirst = checker.check(image);

            assertThat(thirtyFirst.verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
            assertThat(hits.get()).as("31번째는 구글을 부르지 않는다").isEqualTo(30);
            assertThat(redis.opsForValue().get(PERMIT_KEY)).as("허가서 카운터 그대로").isEqualTo("7");
            assertThat(redis.opsForValue().get(MARKET_KEY)).as("사진 검사 카운터는 31까지 센다").isEqualTo("31");
            Long ttl = redis.getExpire(MARKET_KEY);
            assertThat(ttl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(2L * 24 * 60 * 60);
        } finally {
            server.stop(0);
            redis.delete(List.of(MARKET_KEY, PERMIT_KEY));
        }
    }

    @Test
    void 날짜가_바뀌면_새_키로_다시_센다() {
        Clock day1 = Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        Clock day2 = Clock.fixed(Instant.parse("2026-11-19T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        redis.delete(List.of(MARKET_KEY, "market-vision-day:2026-11-19"));
        try {
            assertThat(new MarketSafeSearchDailyLimiter(redis, props(1), day1).tryAcquire()).isTrue();
            assertThat(new MarketSafeSearchDailyLimiter(redis, props(1), day1).tryAcquire()).isFalse();
            assertThat(new MarketSafeSearchDailyLimiter(redis, props(1), day2).tryAcquire()).isTrue();
        } finally {
            redis.delete(List.of(MARKET_KEY, "market-vision-day:2026-11-19"));
        }
    }

    // ---- 가짜 Redis ----

    @Test
    void Redis_오류면_tryAcquire가_false이다() {
        StringRedisTemplate fake = mock(StringRedisTemplate.class);
        when(fake.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenThrow(new RedisConnectionFailureException("boom"));

        assertThat(new MarketSafeSearchDailyLimiter(fake, props(30), CLOCK).tryAcquire()).isFalse();
        verify(fake).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void 상한이_0이면_Redis를_건드리지_않고_true이다() {
        StringRedisTemplate fake = mock(StringRedisTemplate.class);

        assertThat(new MarketSafeSearchDailyLimiter(fake, props(0), CLOCK).tryAcquire()).isTrue();

        verifyNoInteractions(fake);
    }

    @Test
    void 서울_자정을_넘은_시각이면_다음_날_market_키를_쓴다() {
        StringRedisTemplate fake = mock(StringRedisTemplate.class);
        doReturn(1L).when(fake).execute(any(RedisScript.class), anyList(), any(Object[].class));
        Clock seoulNextDay = Clock.fixed(Instant.parse("2026-11-18T15:30:00Z"), ZoneId.of("Asia/Seoul"));

        assertThat(new MarketSafeSearchDailyLimiter(fake, props(30), seoulNextDay).tryAcquire()).isTrue();

        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(fake).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue()).containsExactly("market-vision-day:2026-11-19");
    }
}
