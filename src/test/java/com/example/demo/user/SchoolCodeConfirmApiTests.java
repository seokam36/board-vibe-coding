package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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
import com.example.demo.user.service.SchoolEmailHash;

import jakarta.servlet.http.Cookie;

// S10-c 완료 조건 1~11: POST /users/me/school-verification (학교 메일 인증 코드 확인)
// 코드는 Redis에 Hash를 직접 넣어 시나리오를 만든다(발송기 없이 코드를 알고 있는 상태를 만들기 쉽다).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SchoolCodeConfirmApiTests {

	private static final String URL = "/users/me/school-verification";
	private static final String 맞는코드 = "123456";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;

	// 테스트끼리 Redis 키·DB가 서로 영향 주지 않게 매번 비운다
	@BeforeEach
	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
		for (String pattern : new String[] { "refresh:*", "user-refresh:*", "email-verify*" }) {
			Set<String> keys = redis.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}

	private static String sha256(String s) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	private Long 연세대_id() {
		return jdbc.queryForObject("select id from schools where name = '연세대학교'", Long.class);
	}

	private String 코드키(User u) {
		return "email-verify:" + u.getId();
	}

	// Redis에 "이 회원이 이 메일로 코드를 받은" 상태를 직접 만든다(TTL 10분)
	private void 코드_심기(User u, String code, String email) throws Exception {
		Map<String, String> hash = new HashMap<>();
		hash.put("codeHash", sha256(u.getId() + ":" + code));
		hash.put("email", email);
		hash.put("schoolId", String.valueOf(연세대_id()));
		hash.put("attempts", "0");
		redis.opsForHash().putAll(코드키(u), hash);
		redis.expire(코드키(u), 10, TimeUnit.MINUTES);
	}

	private MockHttpServletResponse 확인(Cookie cookie, String json) throws Exception {
		return mockMvc.perform(post(URL).with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(json))
				.andReturn().getResponse();
	}

	private MockHttpServletResponse 확인코드(Cookie cookie, String code) throws Exception {
		return 확인(cookie, "{\"code\":\"" + code + "\"}");
	}

	private String 본문(MockHttpServletResponse r) throws Exception {
		// 오류 문구는 본문이 아니라 sendError의 사유(reason)로 온다 -> 둘을 합쳐 확인
		return r.getContentAsString(StandardCharsets.UTF_8) + String.valueOf(r.getErrorMessage());
	}

	private Map<String, Object> db행(User u) {
		return jdbc.queryForMap("select verified_school_id, verified_at, school_email_hash from users where id = ?", u.getId());
	}

	// ---------- 완료 조건 1 ----------

	@Test
	void 올바른_코드면_200이고_DB_응답_Redis_me가_모두_인증_상태가_된다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c1");
		Cookie c = TestLogin.쿠키(tokenService, u);
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");

		MockHttpServletResponse res = 확인코드(c, 맞는코드);
		assertThat(res.getStatus()).isEqualTo(200);
		String body = 본문(res);
		assertThat(body).contains("\"name\":\"연세대학교\"");
		assertThat(body).contains("\"id\":" + 연세대_id());

		Map<String, Object> row = db행(u);
		assertThat(((Number) row.get("verified_school_id")).longValue()).isEqualTo(연세대_id());
		assertThat(row.get("verified_at")).isNotNull();
		assertThat(row.get("school_email_hash")).isEqualTo(sha256("a@yonsei.ac.kr"));
		assertThat(redis.hasKey(코드키(u))).as("코드 키 삭제").isFalse();

		// 이후 /auth/me에도 반영
		MockHttpServletResponse me = mockMvc.perform(get("/auth/me").cookie(c)).andReturn().getResponse();
		assertThat(me.getStatus()).isEqualTo(200);
		assertThat(본문(me)).contains("\"name\":\"연세대학교\"");
	}

	// ---------- 완료 조건 2 ----------

	@Test
	void 틀린_코드는_400이고_키가_남고_attempts가_1이다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c2a");
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		MockHttpServletResponse res = 확인코드(TestLogin.쿠키(tokenService, u), "000000");
		assertThat(res.getStatus()).isEqualTo(400);
		assertThat(본문(res)).contains("인증 코드가 올바르지 않습니다.");
		assertThat(redis.hasKey(코드키(u))).isTrue();
		assertThat(redis.opsForHash().get(코드키(u), "attempts")).isEqualTo("1");
	}

	@Test
	void 다섯_번_틀리면_폐기되고_여섯번째와_올바른_코드도_410이다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c2b");
		Cookie c = TestLogin.쿠키(tokenService, u);
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		for (int i = 1; i <= 4; i++) {
			MockHttpServletResponse r = 확인코드(c, "000000");
			assertThat(r.getStatus()).as(i + "번째").isEqualTo(400);
			assertThat(본문(r)).as(i + "번째 문구").contains("인증 코드가 올바르지 않습니다.");
		}
		MockHttpServletResponse fifth = 확인코드(c, "000000");
		assertThat(fifth.getStatus()).isEqualTo(410);
		assertThat(본문(fifth)).contains("폐기");
		assertThat(redis.hasKey(코드키(u))).as("5번째 후 키 삭제").isFalse();

		MockHttpServletResponse sixth = 확인코드(c, "000000");
		assertThat(sixth.getStatus()).isEqualTo(410);
		assertThat(본문(sixth)).contains("없거나 만료");

		MockHttpServletResponse right = 확인코드(c, 맞는코드);
		assertThat(right.getStatus()).as("폐기 뒤 올바른 코드").isEqualTo(410);
		assertThat(db행(u).get("verified_school_id")).isNull();
	}

	// ---------- 완료 조건 3 ----------

	@Test
	void 코드를_받은_적_없으면_410이다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c3a");
		MockHttpServletResponse res = 확인코드(TestLogin.쿠키(tokenService, u), 맞는코드);
		assertThat(res.getStatus()).isEqualTo(410);
		assertThat(본문(res)).contains("없거나 만료");
	}

	@Test
	void 만료된_코드는_410이다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c3b");
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		redis.expire(코드키(u), 300, TimeUnit.MILLISECONDS); // 짧은 TTL로 만료를 흉내
		Thread.sleep(800);
		MockHttpServletResponse res = 확인코드(TestLogin.쿠키(tokenService, u), 맞는코드);
		assertThat(res.getStatus()).isEqualTo(410);
		assertThat(본문(res)).contains("없거나 만료");
		assertThat(db행(u).get("verified_school_id")).isNull();
	}

	// ---------- 완료 조건 4 ----------

	@Test
	void 성공_뒤_같은_코드를_다시_보내면_409다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c4");
		Cookie c = TestLogin.쿠키(tokenService, u);
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		assertThat(확인코드(c, 맞는코드).getStatus()).isEqualTo(200);
		MockHttpServletResponse again = 확인코드(c, 맞는코드);
		assertThat(again.getStatus()).isEqualTo(409);
		assertThat(본문(again)).contains("이미 학교 인증");
	}

	// ---------- 완료 조건 5 ----------

	private void 형식오류는_400이고_attempts가_그대로다(String user, String json) throws Exception {
		User u = TestLogin.사용자(userRepository, user);
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		assertThat(확인(TestLogin.쿠키(tokenService, u), json).getStatus()).as(json).isEqualTo(400);
		assertThat(redis.opsForHash().get(코드키(u), "attempts")).as("attempts 변화 없음").isEqualTo("0");
		assertThat(db행(u).get("verified_school_id")).isNull();
	}

	@Test
	void 다섯_자리_코드는_400이다() throws Exception {
		형식오류는_400이고_attempts가_그대로다("c5a", "{\"code\":\"12345\"}");
	}

	@Test
	void 일곱_자리_코드는_400이다() throws Exception {
		형식오류는_400이고_attempts가_그대로다("c5b", "{\"code\":\"1234567\"}");
	}

	@Test
	void 문자_코드는_400이다() throws Exception {
		형식오류는_400이고_attempts가_그대로다("c5c", "{\"code\":\"abcdef\"}");
	}

	@Test
	void 빈_코드는_400이다() throws Exception {
		형식오류는_400이고_attempts가_그대로다("c5d", "{\"code\":\"\"}");
	}

	@Test
	void code_누락은_400이다() throws Exception {
		형식오류는_400이고_attempts가_그대로다("c5e", "{}");
	}

	@Test
	void 아랍_숫자_코드는_400이다() throws Exception {
		형식오류는_400이고_attempts가_그대로다("c5f", "{\"code\":\"١٢٣٤٥٦\"}");
	}

	@Test
	void 앞뒤_공백이_있는_코드는_정상_처리된다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c5g");
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		assertThat(확인(TestLogin.쿠키(tokenService, u), "{\"code\":\" 123456 \"}").getStatus()).isEqualTo(200);
		assertThat(db행(u).get("verified_school_id")).isNotNull();
	}

	// ---------- 완료 조건 6 ----------

	@Test
	void 이미_인증된_회원의_확인_요청은_409이고_Redis_코드가_소모되지_않는다() throws Exception {
		User u = TestLogin.인증된_사용자(userRepository, jdbc, "c6", "연세대학교");
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		assertThat(확인코드(TestLogin.쿠키(tokenService, u), 맞는코드).getStatus()).isEqualTo(409);
		assertThat(redis.hasKey(코드키(u))).as("코드 남아 있음").isTrue();
		assertThat(redis.opsForHash().get(코드키(u), "attempts")).isEqualTo("0");
	}

	// ---------- 완료 조건 7 ----------

	@Test
	void 같은_회원의_같은_코드_동시_두_요청은_하나만_200이고_DB에_한_번만_기록된다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c7");
		Cookie c = TestLogin.쿠키(tokenService, u);
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		List<Integer> statuses = 동시에(List.of(() -> 확인코드(c, 맞는코드).getStatus(), () -> 확인코드(c, 맞는코드).getStatus()));
		assertThat(statuses.stream().filter(s -> s == 200).count()).as("200은 하나: " + statuses).isEqualTo(1);
		assertThat(statuses.stream().filter(s -> s == 410 || s == 409).count()).as("나머지 410/409: " + statuses).isEqualTo(1);
		assertThat(db행(u).get("verified_at")).isNotNull();
		assertThat(db행(u).get("school_email_hash")).isEqualTo(sha256("a@yonsei.ac.kr"));
	}

	// ---------- 완료 조건 8 ----------

	@Test
	void 두_회원이_같은_메일로_동시에_확인하면_하나만_200이고_해시는_한_명만_갖는다() throws Exception {
		User a = TestLogin.사용자(userRepository, "c8a");
		User b = TestLogin.사용자(userRepository, "c8b");
		Cookie ca = TestLogin.쿠키(tokenService, a);
		Cookie cb = TestLogin.쿠키(tokenService, b);
		코드_심기(a, 맞는코드, "same@yonsei.ac.kr");
		코드_심기(b, 맞는코드, "same@yonsei.ac.kr");
		List<Integer> statuses = 동시에(List.of(() -> 확인코드(ca, 맞는코드).getStatus(), () -> 확인코드(cb, 맞는코드).getStatus()));
		assertThat(statuses).as("상태코드").containsExactlyInAnyOrder(200, 409);
		Integer count = jdbc.queryForObject("select count(*) from users where school_email_hash = ?", Integer.class,
				sha256("same@yonsei.ac.kr"));
		assertThat(count).isEqualTo(1);
	}

	// ---------- 완료 조건 9 ----------

	@Test
	void 다른_회원이_이미_같은_메일로_인증돼_있으면_409이고_내_DB_칸은_NULL이다() throws Exception {
		User other = TestLogin.사용자(userRepository, "c9other");
		jdbc.update("update users set school_email_hash = ? where id = ?", SchoolEmailHash.of("dup@yonsei.ac.kr"), other.getId());
		User me = TestLogin.사용자(userRepository, "c9me");
		코드_심기(me, 맞는코드, "dup@yonsei.ac.kr");
		assertThat(확인코드(TestLogin.쿠키(tokenService, me), 맞는코드).getStatus()).isEqualTo(409);
		Map<String, Object> row = db행(me);
		assertThat(row.get("verified_school_id")).isNull();
		assertThat(row.get("verified_at")).isNull();
		assertThat(row.get("school_email_hash")).isNull();
	}

	// ---------- 완료 조건 10 ----------

	@Test
	void 비로그인은_401이다() throws Exception {
		int status = mockMvc.perform(post(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"123456\"}"))
				.andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(401);
	}

	@Test
	void 유령_토큰은_401이다() throws Exception {
		assertThat(확인코드(TestLogin.행없는_회원_토큰(jwtEncoder), 맞는코드).getStatus()).isEqualTo(401);
	}

	@Test
	void CSRF_토큰이_없으면_403이고_코드는_소모되지_않는다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c10");
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		int status = mockMvc.perform(post(URL).cookie(TestLogin.쿠키(tokenService, u)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"code\":\"123456\"}")).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(403);
		assertThat(redis.hasKey(코드키(u))).isTrue();
		assertThat(db행(u).get("verified_school_id")).isNull();
	}

	// ---------- 완료 조건 11 ----------
	// markVerified 직전 탈퇴 경쟁은 MockMvc로 시점을 맞출 수 없어 직접 재현하지 않는다(생략).
	// 대신 "Redis엔 코드가 있는데 회원 행이 없는" 상태로 요청하면 requireUser에서 401이고 코드는 소모되지 않는다.
	@Test
	void 회원_행이_삭제된_뒤_요청하면_401이다() throws Exception {
		User u = TestLogin.사용자(userRepository, "c11");
		Cookie c = TestLogin.쿠키(tokenService, u);
		코드_심기(u, 맞는코드, "a@yonsei.ac.kr");
		userRepository.deleteById(u.getId());
		assertThat(확인코드(c, 맞는코드).getStatus()).isEqualTo(401);
		assertThat(redis.hasKey(코드키(u))).as("401이면 코드를 소모하지 않음").isTrue();
	}

	// ---------- 도우미: 동시 실행 ----------

	private List<Integer> 동시에(List<Callable<Integer>> jobs) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(jobs.size());
		try {
			CountDownLatch ready = new CountDownLatch(jobs.size());
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> job : jobs) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await();
					return job.call();
				}));
			}
			ready.await();
			go.countDown();
			List<Integer> result = new ArrayList<>();
			for (Future<Integer> f : futures) {
				result.add(f.get(20, TimeUnit.SECONDS));
			}
			return result;
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}
}
