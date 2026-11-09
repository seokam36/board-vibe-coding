package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.user.service.EmailVerificationCodeStore;
import com.example.demo.user.service.SchoolEmailHash;
import com.example.demo.user.service.VerificationMailSender;

import jakarta.servlet.http.Cookie;

// S10-b 완료 조건 1~11: POST /users/me/school-verification/code (학교 메일 인증 코드 보내기)
// + S10 리뷰 반영: 발송 -> 확인 API를 끝까지 잇는 흐름 테스트 1개(파일 맨 끝)
@SpringBootTest
@Import({ TestcontainersConfiguration.class, SchoolEmailCodeApiTests.RecordingSenderConfig.class })
@AutoConfigureMockMvc
class SchoolEmailCodeApiTests {

	// 보낸 코드를 꺼내 보기 위한 가짜 발송기. 로그 발송기 대신 쓰이고(@Primary), 필요하면 예외를 던진다.
	static class RecordingSender implements VerificationMailSender {
		final List<String[]> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
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

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired RecordingSender sender;
	@Autowired EmailVerificationCodeStore codeStore;

	// 테스트끼리 Redis 키·발송 기록이 서로 영향 주지 않게 매번 비운다
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

	private int 요청JSON(Cookie cookie, String json) throws Exception {
		return mockMvc.perform(post(URL).with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(json))
				.andReturn().getResponse().getStatus();
	}

	private int 요청(Cookie cookie, String email) throws Exception {
		return 요청JSON(cookie, "{\"email\":\"" + email + "\"}");
	}

	private String 코드키(User u) {
		return "email-verify:" + u.getId();
	}

	private String 쿨다운키(User u) {
		return "email-verify-cooldown:" + u.getId();
	}

	private String 하루키(User u) {
		return "email-verify-daily:" + u.getId();
	}

	private Long 연세대_id() {
		return jdbc.queryForObject("select id from schools where name = '연세대학교'", Long.class);
	}

	private static String sha256(String s) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	private void 키_없음_발송_없음(User u) {
		assertThat(redis.hasKey(코드키(u))).as("코드 키").isFalse();
		assertThat(redis.hasKey(쿨다운키(u))).as("쿨다운 키").isFalse();
		assertThat(sender.sent).as("발송 기록").isEmpty();
	}

	// ---------- 완료 조건 1 ----------

	@Test
	void 연세대_메일로_요청하면_202와_Redis_저장과_6자리_코드_발송이_된다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code1");
		assertThat(요청(TestLogin.쿠키(tokenService, u), "a@yonsei.ac.kr")).isEqualTo(202);

		Long ttl = redis.getExpire(코드키(u), TimeUnit.SECONDS);
		assertThat(ttl).as("코드 키 TTL").isGreaterThan(0L).isLessThanOrEqualTo(600L);
		Map<Object, Object> hash = redis.opsForHash().entries(코드키(u));
		assertThat(hash.get("schoolId")).isEqualTo(String.valueOf(연세대_id()));
		assertThat(hash.get("attempts")).isEqualTo("0");
		assertThat(hash.get("email")).isEqualTo("a@yonsei.ac.kr");

		assertThat(sender.sent).hasSize(1);
		String code = sender.sent.get(0)[1];
		assertThat(code).matches("\\d{6}");
		assertThat(sender.sent.get(0)[0]).isEqualTo("a@yonsei.ac.kr");
		// 평문 코드는 어느 값에도 없고, codeHash만 SHA-256("{id}:{code}")와 같다
		assertThat(hash.values()).doesNotContain(code);
		assertThat(hash.get("codeHash")).isEqualTo(sha256(u.getId() + ":" + code));
	}

	// ---------- 완료 조건 2 ----------

	@Test
	void 대문자와_공백이_섞인_메일도_202이고_저장_메일은_정규화된다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code2");
		assertThat(요청(TestLogin.쿠키(tokenService, u), " A@Yonsei.AC.KR ")).isEqualTo(202);
		assertThat(redis.opsForHash().get(코드키(u), "email")).isEqualTo("a@yonsei.ac.kr");
	}

	// ---------- 완료 조건 3 ----------

	@Test
	void 목록에_없는_도메인이면_400이고_저장도_발송도_없다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code3a");
		assertThat(요청(TestLogin.쿠키(tokenService, u), "a@gmail.com")).isEqualTo(400);
		키_없음_발송_없음(u);
	}

	@Test
	void 골뱅이_없는_문자열이면_400이고_저장도_발송도_없다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code3b");
		assertThat(요청(TestLogin.쿠키(tokenService, u), "abc")).isEqualTo(400);
		키_없음_발송_없음(u);
	}

	@Test
	void 빈_문자열이면_400이고_저장도_발송도_없다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code3c");
		assertThat(요청(TestLogin.쿠키(tokenService, u), "")).isEqualTo(400);
		키_없음_발송_없음(u);
	}

	@Test
	void email이_누락되면_400이고_저장도_발송도_없다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code3d");
		assertThat(요청JSON(TestLogin.쿠키(tokenService, u), "{}")).isEqualTo(400);
		키_없음_발송_없음(u);
	}

	// ---------- 완료 조건 4 ----------

	@Test
	void 이미_인증된_회원은_409다() throws Exception {
		User u = TestLogin.인증된_사용자(userRepository, jdbc, "code4", "연세대학교");
		assertThat(요청(TestLogin.쿠키(tokenService, u), "a@yonsei.ac.kr")).isEqualTo(409);
		키_없음_발송_없음(u);
	}

	// ---------- 완료 조건 5 ----------

	@Test
	void 다른_회원이_같은_메일_해시를_쓰고_있으면_대소문자가_달라도_409다() throws Exception {
		User other = TestLogin.사용자(userRepository, "code5other");
		jdbc.update("update users set school_email_hash = ? where id = ?", SchoolEmailHash.of("dup@yonsei.ac.kr"), other.getId());
		User me = TestLogin.사용자(userRepository, "code5me");
		Cookie c = TestLogin.쿠키(tokenService, me);

		assertThat(요청(c, "dup@yonsei.ac.kr")).isEqualTo(409);
		assertThat(요청(c, "DUP@Yonsei.AC.KR")).isEqualTo(409);
		키_없음_발송_없음(me);
	}

	// ---------- 완료 조건 6 ----------

	@Test
	void 곧바로_다시_요청하면_429이고_기존_코드가_그대로다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code6a");
		Cookie c = TestLogin.쿠키(tokenService, u);
		assertThat(요청(c, "a@yonsei.ac.kr")).isEqualTo(202);
		Object before = redis.opsForHash().get(코드키(u), "codeHash");

		assertThat(요청(c, "a@yonsei.ac.kr")).isEqualTo(429);
		assertThat(redis.opsForHash().get(코드키(u), "codeHash")).isEqualTo(before);
		assertThat(sender.sent).hasSize(1);
	}

	@Test
	void 하루_10번_성공_후_11번째는_429다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code6b");
		Cookie c = TestLogin.쿠키(tokenService, u);
		for (int i = 1; i <= 10; i++) {
			redis.delete(쿨다운키(u)); // 60초를 기다리지 않고 쿨다운만 지운다
			// 메일 주소 기준 상한(5회)에 걸리지 않게 매번 다른 메일을 쓴다 -> 회원 하루 상한만 검증
			assertThat(요청(c, "user" + i + "@yonsei.ac.kr")).as(i + "번째").isEqualTo(202);
		}
		redis.delete(쿨다운키(u));
		assertThat(요청(c, "user11@yonsei.ac.kr")).as("11번째").isEqualTo(429);
		assertThat(sender.sent).hasSize(10);
	}

	// ---------- 완료 조건 7 ----------

	@Test
	void 새_코드를_받으면_옛_codeHash가_바뀐다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code7");
		Cookie c = TestLogin.쿠키(tokenService, u);
		assertThat(요청(c, "a@yonsei.ac.kr")).isEqualTo(202);
		Object old = redis.opsForHash().get(코드키(u), "codeHash");

		redis.delete(쿨다운키(u));
		assertThat(요청(c, "a@yonsei.ac.kr")).isEqualTo(202);
		Object now = redis.opsForHash().get(코드키(u), "codeHash");
		assertThat(now).isNotEqualTo(old);
		// 새 해시는 두 번째로 보낸 코드와 맞고, 옛 코드와는 안 맞는다
		assertThat(now).isEqualTo(sha256(u.getId() + ":" + sender.sent.get(1)[1]));
		assertThat(old).isEqualTo(sha256(u.getId() + ":" + sender.sent.get(0)[1]));
	}

	// ---------- 완료 조건 8 ----------

	@Test
	void 비로그인은_401이다() throws Exception {
		int status = mockMvc.perform(post(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"a@yonsei.ac.kr\"}")).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(401);
		assertThat(sender.sent).isEmpty();
	}

	@Test
	void 유령_토큰은_401이다() throws Exception {
		assertThat(요청(TestLogin.행없는_회원_토큰(jwtEncoder), "a@yonsei.ac.kr")).isEqualTo(401);
		assertThat(sender.sent).isEmpty();
	}

	@Test
	void CSRF_토큰이_없으면_403이다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code8");
		int status = mockMvc.perform(post(URL).cookie(TestLogin.쿠키(tokenService, u)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"a@yonsei.ac.kr\"}")).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(403);
		키_없음_발송_없음(u);
	}

	// ---------- 완료 조건 9 ----------

	@Test
	void 발송기_예외면_500이고_Redis가_되돌려지며_바로_재요청하면_202다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code9");
		Cookie c = TestLogin.쿠키(tokenService, u);
		sender.fail = true;
		assertThat(요청(c, "a@yonsei.ac.kr")).isEqualTo(500);
		assertThat(redis.hasKey(코드키(u))).as("코드 키").isFalse();
		assertThat(redis.hasKey(쿨다운키(u))).as("쿨다운 키").isFalse();
		String daily = redis.opsForValue().get(하루키(u));
		assertThat(daily == null || Long.parseLong(daily) == 0L).as("하루 횟수 되돌림(실제 %s)", daily).isTrue();

		sender.fail = false;
		assertThat(요청(c, "a@yonsei.ac.kr")).isEqualTo(202);
		assertThat(sender.sent).hasSize(1);
	}

	// 남의(나중에 저장된) 코드가 있으면 rollback이 아무것도 지우지 않는다
	@Test
	void rollback은_내_코드가_아니면_키와_쿨다운과_하루_횟수를_건드리지_않는다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code9b");
		assertThat(요청(TestLogin.쿠키(tokenService, u), "a@yonsei.ac.kr")).isEqualTo(202);
		String code = sender.sent.get(0)[1];
		// 다른 요청이 새 코드를 저장한 상황: codeHash를 다른 값으로 바꿔 둔다
		redis.opsForHash().put(코드키(u), "codeHash", "other-hash");
		String daily = redis.opsForValue().get(하루키(u));
		String targetKey = "email-verify-target:" + sha256("a@yonsei.ac.kr");
		String pairKey = "email-verify-pair:" + u.getId() + ":" + sha256("a@yonsei.ac.kr");
		String target = redis.opsForValue().get(targetKey);
		String pair = redis.opsForValue().get(pairKey);

		codeStore.rollback(u.getId(), code, "a@yonsei.ac.kr");

		assertThat(redis.opsForHash().get(코드키(u), "codeHash")).isEqualTo("other-hash");
		assertThat(redis.hasKey(쿨다운키(u))).as("쿨다운 키").isTrue();
		assertThat(redis.opsForValue().get(하루키(u))).as("하루 횟수").isEqualTo(daily);
		assertThat(redis.opsForValue().get(targetKey)).as("메일 하루 횟수").isEqualTo(target).isEqualTo("1");
		assertThat(redis.opsForValue().get(pairKey)).as("회원+메일 하루 횟수").isEqualTo(pair).isEqualTo("1");
	}

	// ---------- 완료 조건 10 ----------

	@Test
	void 같은_회원의_동시_두_요청은_하나만_202이고_나머지는_429다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code10");
		Cookie c = TestLogin.쿠키(tokenService, u);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return 요청(c, "a@yonsei.ac.kr");
				}));
			}
			ready.await();
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> f : futures) {
				statuses.add(f.get(15, TimeUnit.SECONDS));
			}
			assertThat(statuses).containsExactlyInAnyOrder(202, 429);
			assertThat(sender.sent).hasSize(1);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	// ---------- 완료 조건 11 ----------

	@Test
	void 성공해도_DB의_인증_칸은_그대로_NULL이다() throws Exception {
		User u = TestLogin.사용자(userRepository, "code11");
		assertThat(요청(TestLogin.쿠키(tokenService, u), "a@yonsei.ac.kr")).isEqualTo(202);
		Map<String, Object> row = jdbc.queryForMap("select school_email_hash, verified_school_id from users where id = ?", u.getId());
		assertThat(row.get("school_email_hash")).isNull();
		assertThat(row.get("verified_school_id")).isNull();
	}

	// ---------- 발송 -> 확인 전체 흐름 (S10 리뷰 권장) ----------
	// 확인 API 테스트는 Redis에 코드를 직접 심기 때문에, 해시 공식·Redis 필드 이름이 한쪽만 바뀌어도 둘 다 초록이 된다.
	// 그래서 발송 API가 실제로 저장한 코드를 그대로 확인 API에 넣어 끝까지 지나가 본다.
	// DB 해시는 main의 SchoolEmailHash 대신 테스트 쪽 sha256(소문자 메일)로 독립 계산해 비교한다.
	@Test
	void 발송_API가_저장한_코드를_확인_API에_넣으면_인증이_끝까지_완료된다() throws Exception {
		User u = TestLogin.사용자(userRepository, "flow");
		Cookie c = TestLogin.쿠키(tokenService, u);

		// 1) 대문자·공백이 섞인 메일로 코드 요청 -> 202
		assertThat(요청(c, "  A@Yonsei.AC.kr ")).isEqualTo(202);
		assertThat(sender.sent).hasSize(1);
		String code = sender.sent.get(0)[1];
		assertThat(code).matches("\\d{6}");

		// 2) 발송기가 받은 코드를 그대로 확인 API에 -> 200, 연세대학교
		MockHttpServletResponse res = mockMvc.perform(post("/users/me/school-verification").with(csrf()).cookie(c)
				.contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}")).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(res.getContentAsString(StandardCharsets.UTF_8)).contains("\"name\":\"연세대학교\"");

		// 3) DB: 정규화된 메일의 해시, 인증 시각, 인증 학교
		Map<String, Object> row = jdbc.queryForMap(
				"select school_email_hash, verified_at, verified_school_id from users where id = ?", u.getId());
		assertThat(row.get("school_email_hash")).isEqualTo(sha256("a@yonsei.ac.kr"));
		assertThat(row.get("verified_at")).isNotNull();
		assertThat(((Number) row.get("verified_school_id")).longValue()).isEqualTo(연세대_id());

		// 4) /auth/me 에도 연세대학교
		MockHttpServletResponse me = mockMvc.perform(get("/auth/me").cookie(c)).andReturn().getResponse();
		assertThat(me.getStatus()).isEqualTo(200);
		assertThat(me.getContentAsString(StandardCharsets.UTF_8)).contains("\"name\":\"연세대학교\"");

		// 5) 코드 키는 소모되어 사라졌다
		assertThat(redis.hasKey(코드키(u))).as("코드 키 삭제").isFalse();
	}
}
