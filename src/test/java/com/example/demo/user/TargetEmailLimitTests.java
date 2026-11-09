package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.user.service.VerificationMailSender;

import jakarta.servlet.http.Cookie;

// S10-g 완료 조건 1~7: 받는 메일 주소 기준 하루 발송 상한(5회)
@SpringBootTest
@Import({ TestcontainersConfiguration.class, TargetEmailLimitTests.RecordingSenderConfig.class })
@AutoConfigureMockMvc
class TargetEmailLimitTests {

	// 발송 내용을 기록하고, 필요하면 예외를 던지는 가짜 발송기
	static class RecordingSender implements VerificationMailSender {
		final List<String[]> sent = new CopyOnWriteArrayList<>();
		volatile boolean fail = false;

		@Override
		public void sendCode(String email, String code) {
			if (fail) {
				throw new RuntimeException("발송 실패(테스트)");
			}
			sent.add(new String[] { email, code });
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RecordingSenderConfig {
		@Bean
		@Primary
		RecordingSender recordingSender() {
			return new RecordingSender();
		}
	}

	private static final String URL = "/users/me/school-verification/code";
	private static final String MAIL = "a@yonsei.ac.kr";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired StringRedisTemplate redis;
	@Autowired RecordingSender sender;

	// 테스트끼리 Redis 키(메일 상한 키 포함)·발송 기록이 섞이지 않게 매번 비운다
	@BeforeEach
	@AfterEach
	void 정리() {
		sender.fail = false;
		sender.sent.clear();
		postRepository.deleteAll();
		userRepository.deleteAll();
		for (String pattern : new String[] { "refresh:*", "user-refresh:*", "email-verify*" }) {
			Set<String> keys = redis.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}

	private int 요청(Cookie cookie, String email) throws Exception {
		return mockMvc.perform(post(URL).with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\"}")).andReturn().getResponse().getStatus();
	}

	private Cookie 쿠키(User u) {
		return TestLogin.쿠키(tokenService, u);
	}

	private User 회원(String name) {
		return TestLogin.사용자(userRepository, name);
	}

	private String 코드키(User u) {
		return "email-verify:" + u.getId();
	}

	private String 쿨다운키(User u) {
		return "email-verify-cooldown:" + u.getId();
	}

	private String 회원하루키(User u) {
		return "email-verify-daily:" + u.getId();
	}

	private static String sha256(String s) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	private String 메일키(String normalizedEmail) throws Exception {
		return "email-verify-target:" + sha256(normalizedEmail);
	}

	// ---------- 완료 조건 1 ----------

	@Test
	void 서로_다른_회원_5명은_202이고_6번째_회원은_429이며_아무것도_바뀌지_않는다() throws Exception {
		for (int i = 1; i <= 5; i++) {
			assertThat(요청(쿠키(회원("t1u" + i)), MAIL)).as(i + "번째 회원").isEqualTo(202);
		}
		User sixth = 회원("t1u6");
		assertThat(요청(쿠키(sixth), MAIL)).as("6번째 회원").isEqualTo(429);

		// 거절된 요청은 코드·쿨다운·회원 하루 횟수 중 아무것도 만들지 않는다
		assertThat(redis.hasKey(코드키(sixth))).as("코드 키").isFalse();
		assertThat(redis.hasKey(쿨다운키(sixth))).as("쿨다운 키").isFalse();
		assertThat(redis.hasKey(회원하루키(sixth))).as("회원 하루 키").isFalse();
		assertThat(sender.sent).as("발송 기록").hasSize(5);
		assertThat(redis.opsForValue().get(메일키(MAIL))).as("메일 하루 횟수").isEqualTo("5");
	}

	// ---------- 완료 조건 2 ----------

	@Test
	void 대소문자와_공백만_다른_메일도_같은_메일로_합산된다() throws Exception {
		String[] variants = { MAIL, " A@Yonsei.AC.KR ", "A@yonsei.ac.kr", " a@YONSEI.ac.kr", "a@yonsei.ac.kr " };
		for (int i = 0; i < 5; i++) {
			assertThat(요청(쿠키(회원("t2u" + i)), variants[i])).as(i + "번째").isEqualTo(202);
		}
		assertThat(요청(쿠키(회원("t2u6")), " A@Yonsei.AC.KR ")).as("6번째(변형 표기)").isEqualTo(429);
		assertThat(redis.opsForValue().get(메일키(MAIL))).isEqualTo("5");
	}

	// ---------- 완료 조건 3 ----------

	@Test
	void 다른_메일은_상한에_영향받지_않고_202다() throws Exception {
		for (int i = 1; i <= 5; i++) {
			assertThat(요청(쿠키(회원("t3u" + i)), MAIL)).isEqualTo(202);
		}
		assertThat(요청(쿠키(회원("t3x")), MAIL)).as("같은 메일 6번째").isEqualTo(429);
		assertThat(요청(쿠키(회원("t3y")), "b@yonsei.ac.kr")).as("다른 메일").isEqualTo(202);
		assertThat(redis.opsForValue().get(메일키("b@yonsei.ac.kr"))).isEqualTo("1");
	}

	// ---------- 완료 조건 4 ----------

	@Test
	void 메일_상한_키에는_메일_원문이_없고_이름은_SHA256이며_TTL은_24시간_이하다() throws Exception {
		assertThat(요청(쿠키(회원("t4")), " A@Yonsei.AC.KR ")).isEqualTo(202);

		Set<String> keys = redis.keys("email-verify-target:*");
		assertThat(keys).hasSize(1);
		String key = keys.iterator().next();
		// 키 이름 뒤가 정규화 메일의 SHA-256 hex와 같다
		assertThat(key).isEqualTo("email-verify-target:" + sha256("a@yonsei.ac.kr"));
		// 키 이름·값 어디에도 메일 원문이 없다
		assertThat(key.toLowerCase()).doesNotContain("yonsei").doesNotContain("a@");
		String value = redis.opsForValue().get(key);
		assertThat(value).isEqualTo("1");
		assertThat(value.toLowerCase()).doesNotContain("yonsei").doesNotContain("@");
		// TTL: 0초 초과 86400초 이하
		Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
		assertThat(ttl).isGreaterThan(0L).isLessThanOrEqualTo(86400L);
	}

	// ---------- 완료 조건 5 ----------

	@Test
	void 같은_회원이_곧바로_다시_요청하면_429이고_메일_횟수는_늘지_않는다() throws Exception {
		User u = 회원("t5a");
		Cookie c = 쿠키(u);
		assertThat(요청(c, MAIL)).isEqualTo(202);
		assertThat(요청(c, MAIL)).as("쿨다운 중").isEqualTo(429);
		assertThat(redis.opsForValue().get(메일키(MAIL))).as("메일 하루 횟수").isEqualTo("1");
		assertThat(redis.opsForValue().get(회원하루키(u))).as("회원 하루 횟수").isEqualTo("1");
	}

	@Test
	void 회원_하루_10회_상한은_매번_다른_메일로도_기존대로_11번째가_429다() throws Exception {
		User u = 회원("t5b");
		Cookie c = 쿠키(u);
		for (int i = 1; i <= 10; i++) {
			redis.delete(쿨다운키(u)); // 60초를 기다리지 않고 쿨다운만 지운다
			assertThat(요청(c, "user" + i + "@yonsei.ac.kr")).as(i + "번째").isEqualTo(202);
		}
		redis.delete(쿨다운키(u));
		assertThat(요청(c, "user11@yonsei.ac.kr")).as("11번째").isEqualTo(429);
		assertThat(redis.hasKey(메일키("user11@yonsei.ac.kr"))).as("거절된 메일의 횟수 키").isFalse();
		assertThat(sender.sent).hasSize(10);
	}

	// ---------- 완료 조건 6 ----------

	@Test
	void 발송기_예외면_메일_횟수가_되돌려져_그_뒤_같은_메일로_5번_성공할_수_있다() throws Exception {
		sender.fail = true;
		assertThat(요청(쿠키(회원("t6fail")), MAIL)).as("발송 실패").isEqualTo(500);
		String after = redis.opsForValue().get(메일키(MAIL));
		assertThat(after == null || Long.parseLong(after) == 0L).as("되돌린 뒤 메일 횟수(실제 %s)", after).isTrue();

		sender.fail = false;
		for (int i = 1; i <= 5; i++) {
			assertThat(요청(쿠키(회원("t6u" + i)), MAIL)).as(i + "번째 성공").isEqualTo(202);
		}
		assertThat(요청(쿠키(회원("t6u6")), MAIL)).as("총 6번째 성공 시도").isEqualTo(429);
		assertThat(sender.sent).hasSize(5);
	}

	// ---------- 같은 회원 + 같은 메일 하루 2회 ----------

	@Test
	void 같은_회원이_같은_메일로는_하루_2번만_성공하고_3번째는_429이며_메일_횟수는_늘지_않는다() throws Exception {
		User u = 회원("pairU");
		Cookie c = 쿠키(u);
		for (int i = 1; i <= 2; i++) {
			redis.delete(쿨다운키(u)); // 60초를 기다리지 않고 쿨다운만 지운다
			assertThat(요청(c, MAIL)).as(i + "번째").isEqualTo(202);
		}
		redis.delete(쿨다운키(u));
		assertThat(요청(c, MAIL)).as("3번째").isEqualTo(429);
		assertThat(redis.opsForValue().get(메일키(MAIL))).as("메일 하루 횟수").isEqualTo("2");
		assertThat(redis.opsForValue().get(회원하루키(u))).as("회원 하루 횟수").isEqualTo("2");
		// 다른 회원은 같은 메일로 아직 보낼 수 있다
		assertThat(요청(쿠키(회원("pairV")), MAIL)).as("다른 회원").isEqualTo(202);
	}

	// ---------- 완료 조건 7 ----------

	@Test
	void 회원_8명이_같은_메일로_동시에_요청하면_202는_정확히_5개이고_메일_횟수는_5다() throws Exception {
		int n = 8;
		List<Cookie> cookies = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			cookies.add(쿠키(회원("t7u" + i)));
		}
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			CountDownLatch ready = new CountDownLatch(n);
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (Cookie c : cookies) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return 요청(c, MAIL);
				}));
			}
			ready.await();
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : futures) {
				statuses.add(f.get(20, TimeUnit.SECONDS));
			}
			assertThat(statuses.stream().filter(s -> s == 202).count()).as("202 개수, 전체 %s", statuses).isEqualTo(5L);
			assertThat(statuses.stream().filter(s -> s == 429).count()).as("429 개수, 전체 %s", statuses).isEqualTo(3L);
			assertThat(redis.opsForValue().get(메일키(MAIL))).isEqualTo("5");
			assertThat(sender.sent).hasSize(5);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}
}
