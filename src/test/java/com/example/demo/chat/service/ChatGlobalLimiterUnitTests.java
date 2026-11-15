package com.example.demo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.config.ChatProperties;

// S18-d: ChatGlobalLimiter 단독 검사(스프링·Docker 없이). 키 이름 계산은 dayKey·minuteKey를 직접 부르고,
// 한 칸만 0일 때 값·Redis 장애 동작은 가짜 Redis(아래 클래스)로 본다.
class ChatGlobalLimiterUnitTests {

	// Redis 대신 "받은 것을 적어 두고 정해 둔 결과를 돌려주는" 가짜. Mockito 없이 읽기 쉽게 직접 만들었다.
	static class 가짜Redis extends StringRedisTemplate {
		Long 돌려줄_결과 = 0L;
		RuntimeException 던질_예외;
		int 호출수;
		List<String> 받은_키;
		List<Object> 받은_인자;

		@Override
		@SuppressWarnings("unchecked")
		public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
			호출수++;
			받은_키 = keys;
			받은_인자 = List.of(args);
			if (던질_예외 != null) {
				throw 던질_예외;
			}
			return (T) 돌려줄_결과;
		}
	}

	private ChatGlobalLimiter 만들기(가짜Redis redis, int 분, int 하루) {
		ChatProperties props = new ChatProperties(new ChatProperties.RateLimit(5, 30), new ChatProperties.GlobalLimit(분, 하루), 2,
				Duration.ofSeconds(5));
		return new ChatGlobalLimiter(redis, props, Clock.fixed(Instant.parse("2026-11-15T00:00:59Z"), ZoneOffset.UTC));
	}

	@Test
	void 둘_다_0이면_Redis를_부르지_않는다() {
		가짜Redis redis = new 가짜Redis();

		만들기(redis, 0, 0).countOrReject();

		assertThat(redis.호출수).isZero();
	}

	@Test
	void 한_칸만_0이면_그_칸의_상한은_int_최댓값으로_넘긴다() {
		가짜Redis 분만_끔 = new 가짜Redis();
		만들기(분만_끔, 0, 200).countOrReject();
		assertThat(분만_끔.받은_인자.subList(0, 2)).containsExactly(String.valueOf(Integer.MAX_VALUE), "200");

		가짜Redis 하루만_끔 = new 가짜Redis();
		만들기(하루만_끔, 7, 0).countOrReject();
		assertThat(하루만_끔.받은_인자.subList(0, 2)).containsExactly("7", String.valueOf(Integer.MAX_VALUE));
	}

	@Test
	void 켜져_있으면_설정한_상한_그대로_넘긴다() {
		가짜Redis redis = new 가짜Redis();

		만들기(redis, 7, 200).countOrReject();

		assertThat(redis.받은_인자).containsExactly("7", "200", "120", "93600"); // 분 TTL 2분, 하루 TTL 26시간(초)
	}

	@Test
	void 분_키는_epoch_분_번호다() {
		Instant 지금 = Instant.parse("2026-11-15T00:00:59Z");

		assertThat(ChatGlobalLimiter.minuteKey(지금)).isEqualTo("chat-global-min:" + 지금.getEpochSecond() / 60);
	}

	@Test
	void countOrReject는_분_키와_하루_키를_순서대로_넘긴다() {
		가짜Redis redis = new 가짜Redis();

		만들기(redis, 7, 200).countOrReject(); // 시계 2026-11-15T00:00:59Z = 태평양 11-14 16:00

		assertThat(redis.받은_키).containsExactly(ChatGlobalLimiter.minuteKey(Instant.parse("2026-11-15T00:00:59Z")),
				"chat-global-day:2026-11-14");
	}

	// 서머타임 끝(2026-11-01, 하루 25시간)·시작(2026-03-08, 하루 23시간) 전후에도 하루 키는 태평양 날짜다.
	@Test
	void 서머타임_전환일_앞뒤에도_하루_키는_태평양_날짜다() {
		String[][] 경우 = {
				{ "2026-11-01T08:30:00Z", "2026-11-01" }, // 01:30 PDT(전환 직전)
				{ "2026-11-01T09:30:00Z", "2026-11-01" }, // 01:30 PST(같은 시각이 한 번 더)
				{ "2026-11-02T07:59:00Z", "2026-11-01" }, // 11-01 23:59 PST = 25시간짜리 날의 마지막 순간
				{ "2026-11-02T08:00:00Z", "2026-11-02" }, // 자정 -> 날짜가 바뀐다
				{ "2026-03-08T09:00:00Z", "2026-03-08" }, // 01:00 PST(전환 전)
				{ "2026-03-08T11:00:00Z", "2026-03-08" }, // 04:00 PDT(전환 후)
				{ "2026-03-09T06:59:00Z", "2026-03-08" }, // 03-08 23:59 PDT
				{ "2026-03-09T07:00:00Z", "2026-03-09" } };
		for (String[] c : 경우) {
			assertThat(ChatGlobalLimiter.dayKey(Instant.parse(c[0]))).as(c[0]).isEqualTo("chat-global-day:" + c[1]);
		}
	}

	@Test
	void Lua_결과가_1이나_2이면_503이다() {
		for (long 결과 : new long[] { 1L, 2L }) {
			가짜Redis redis = new 가짜Redis();
			redis.돌려줄_결과 = 결과;
			ChatGlobalLimiter limiter = 만들기(redis, 7, 200);

			assertThatThrownBy(limiter::countOrReject).isInstanceOfSatisfying(ResponseStatusException.class,
					e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
		}
	}

	// 회원별 제한(ChatRateLimiter)과 같게: Redis가 실패하면 예외가 그대로 올라가 500이 된다(조용히 통과시키지 않는다)
	@Test
	void Redis가_실패하면_예외가_그대로_올라가고_결과가_null이어도_IllegalState다() {
		가짜Redis 장애 = new 가짜Redis();
		장애.던질_예외 = new RedisConnectionFailureException("테스트용 Redis 장애");
		assertThatThrownBy(() -> 만들기(장애, 7, 200).countOrReject()).isInstanceOf(RedisConnectionFailureException.class);

		가짜Redis 빈결과 = new 가짜Redis();
		빈결과.돌려줄_결과 = null;
		assertThatThrownBy(() -> 만들기(빈결과, 7, 200).countOrReject()).isInstanceOf(IllegalStateException.class);
	}
}
