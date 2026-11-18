package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.example.demo.config.PermitOcrConfig;
import com.example.demo.config.PermitOcrProperties;
import com.example.demo.permit.service.PermitVisionDailyLimiter;

// S21-d: DB·Redis 없이 보는 단위 검사. (7) Redis 오류면 false (6) 상한 음수면 시작 실패, 0·양수는 정상.
@SuppressWarnings({ "unchecked", "rawtypes" })
class PermitVisionDailyLimiterUnitTests {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-18T03:00:00Z"), ZoneId.of("Asia/Seoul"));

	private static PermitOcrProperties props(String mode, int limit) {
		return new PermitOcrProperties(mode, new PermitOcrProperties.Vision("http://127.0.0.1:1", "k", null, 2, null, limit));
	}

	// 완료 조건 7
	@Test
	void Redis_오류면_tryAcquire가_false이다() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
				.thenThrow(new RedisConnectionFailureException("boom"));

		boolean ok = new PermitVisionDailyLimiter(redis, props("vision", 2), CLOCK).tryAcquire();

		assertThat(ok).isFalse();
		// 예외가 정말 Redis 호출에서 났는지(스텁이 맞게 걸렸는지) 확인
		verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	// 완료 조건 4·5 (Redis를 안 건드림)
	@Test
	void 상한이_0이거나_mode가_off면_Redis를_건드리지_않고_true이다() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);

		assertThat(new PermitVisionDailyLimiter(redis, props("vision", 0), CLOCK).tryAcquire()).isTrue();
		assertThat(new PermitVisionDailyLimiter(redis, props("off", 2), CLOCK).tryAcquire()).isTrue();

		verifyNoInteractions(redis);
	}

	// 권장5-a: 서울 날짜 경계. UTC 15:30은 서울로 이미 다음 날 00:30이라 키가 11-19여야 한다
	@Test
	void 서울_자정을_넘은_시각이면_다음_날_날짜_키를_쓴다() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
		Clock seoulNextDay = Clock.fixed(Instant.parse("2026-11-18T15:30:00Z"), ZoneId.of("Asia/Seoul"));

		boolean ok = new PermitVisionDailyLimiter(redis, props("vision", 2), seoulNextDay).tryAcquire();

		assertThat(ok).isTrue();
		ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
		verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
		assertThat(keys.getValue()).containsExactly("permit-vision-day:2026-11-19");
	}

	// 권장5-c: off가 아닌 모드(모르는 값 포함)는 센다 = Redis를 부른다
	@Test
	void off가_아닌_모르는_모드도_센다() {
		StringRedisTemplate redis = mock(StringRedisTemplate.class);
		doReturn(1L).when(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));

		boolean ok = new PermitVisionDailyLimiter(redis, props("other", 2), CLOCK).tryAcquire();

		assertThat(ok).isTrue();
		verify(redis).execute(any(RedisScript.class), anyList(), any(Object[].class));
	}

	private static String 뿌리메시지(Throwable t) {
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return String.valueOf(t.getMessage());
	}

	// 완료 조건 6: 상한 음수면 시작 실패(메시지 확인), 0·양수는 정상. mode가 off여도 막는다.
	@Test
	void 상한이_음수면_앱_시작이_실패하고_0과_양수는_정상이다() {
		ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PermitOcrConfig.class);
		String msg = "VISION_GLOBAL_PER_DAY는 0 이상이어야 합니다";

		runner.withPropertyValues("app.permit.ocr.vision.global-per-day=-1").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(뿌리메시지(ctx.getStartupFailure())).contains(msg);
		});
		runner.withPropertyValues("app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=TESTKEY-never-real-1",
				"app.permit.ocr.vision.base-url=http://127.0.0.1:1", "app.permit.ocr.vision.global-per-day=-5")
				.run(ctx -> {
					assertThat(ctx).hasFailed();
					assertThat(뿌리메시지(ctx.getStartupFailure())).contains(msg);
				});
		runner.withPropertyValues("app.permit.ocr.vision.global-per-day=0").run(ctx -> assertThat(ctx).hasNotFailed());
		runner.withPropertyValues("app.permit.ocr.vision.global-per-day=30").run(ctx -> assertThat(ctx).hasNotFailed());
	}
}
