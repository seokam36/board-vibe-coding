package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.RefreshTokenService;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S4-c 테스트: Redis 리프레시 토큰(발급·회전·삭제·동시성)을 MockMvc + 실제 Redis 컨테이너로 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class RefreshTokenTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	RefreshTokenService refreshTokenService;
	@Autowired
	JwtEncoder jwtEncoder;

	private final ObjectMapper om = new ObjectMapper();

	// 테스트끼리 Redis 키가 섞이지 않도록 매번 비운다
	@BeforeEach
	@AfterEach
	void 정리() {
		userRepository.deleteAll();
		Set<String> keys = redis.keys("refresh:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	// ---------- 도우미 ----------

	private String json(String u, String p) {
		return "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}";
	}

	private void 가입(String u) throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json(u, "password123"))).andReturn();
	}

	private MockHttpServletResponse 로그인(String u) throws Exception {
		return mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json(u, "password123"))).andReturn().getResponse();
	}

	private MockHttpServletResponse 재발급(String refresh) throws Exception {
		var req = post("/auth/refresh").with(csrf());
		if (refresh != null) {
			req = req.cookie(new Cookie("refresh_token", refresh));
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private String 쿠키값(MockHttpServletResponse res, String name) {
		Cookie c = res.getCookie(name);
		return c == null ? null : c.getValue();
	}

	// Set-Cookie 헤더 중 해당 이름으로 시작하는 한 줄
	private String 헤더(MockHttpServletResponse res, String name) {
		return res.getHeaders(HttpHeaders.SET_COOKIE).stream().filter(h -> h.startsWith(name + "=")).findFirst()
				.orElse(null);
	}

	private String 해시키(String token) throws Exception {
		byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
		return "refresh:" + HexFormat.of().formatHex(h);
	}

	private Set<String> 키목록() {
		return redis.keys("refresh:*");
	}

	private void 삭제쿠키_확인(MockHttpServletResponse res) {
		String a = 헤더(res, "access_token");
		String r = 헤더(res, "refresh_token");
		assertThat(a).isNotNull().contains("Max-Age=0");
		assertThat(r).isNotNull().contains("Max-Age=0", "Path=/api/auth");
	}

	// ---------- 완료 조건 1: 로그인 발급 ----------

	@Test
	void 로그인하면_access와_refresh_쿠키가_속성과_함께_발급된다() throws Exception {
		가입("user01");
		MockHttpServletResponse res = 로그인("user01");
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(헤더(res, "access_token")).isNotNull();
		String r = 헤더(res, "refresh_token");
		assertThat(r).isNotNull().contains("HttpOnly", "SameSite=Lax", "Path=/api/auth", "Max-Age=604800");
	}

	@Test
	void 로그인하면_Redis에_토큰_해시_키가_생기고_값은_userId_TTL은_약_7일이다() throws Exception {
		가입("user01");
		MockHttpServletResponse res = 로그인("user01");
		String token = 쿠키값(res, "refresh_token");
		String key = 해시키(token);
		assertThat(redis.hasKey(key)).isTrue();
		assertThat(redis.opsForValue().get(key))
				.isEqualTo(String.valueOf(userRepository.findAll().get(0).getId()));
		Long ttl = redis.getExpire(key); // 초 단위
		assertThat(ttl).isBetween(604800L - 60, 604800L);
	}

	@Test
	void Redis_키와_값_어디에도_토큰_원문이_없다() throws Exception {
		가입("user01");
		String token = 쿠키값(로그인("user01"), "refresh_token");
		Set<String> keys = 키목록();
		assertThat(keys).hasSize(1);
		for (String k : keys) {
			assertThat(k).doesNotContain(token);
			assertThat(redis.opsForValue().get(k)).doesNotContain(token);
		}
	}

	// ---------- 완료 조건 2: 재발급 성공 ----------

	@Test
	void 유효한_refresh로_재발급하면_200과_새_쿠키_두_개와_키_교체_그리고_me_200() throws Exception {
		가입("user01");
		String old = 쿠키값(로그인("user01"), "refresh_token");
		MockHttpServletResponse res = 재발급(old);
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode body = om.readTree(res.getContentAsString());
		assertThat(body.get("id").asLong()).isEqualTo(userRepository.findAll().get(0).getId());
		assertThat(body.get("username").asText()).isEqualTo("user01");
		String newRefresh = 쿠키값(res, "refresh_token");
		String newAccess = 쿠키값(res, "access_token");
		assertThat(newRefresh).isNotBlank().isNotEqualTo(old);
		assertThat(newAccess).isNotBlank();
		// 옛 키는 사라지고 새 키가 생긴다
		assertThat(redis.hasKey(해시키(old))).isFalse();
		assertThat(redis.hasKey(해시키(newRefresh))).isTrue();
		// 새 access_token으로 내 정보 조회
		int me = mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", newAccess))).andReturn()
				.getResponse().getStatus();
		assertThat(me).isEqualTo(200);
	}

	// ---------- 완료 조건 3: 회전 ----------

	@Test
	void 같은_옛_refresh로_두번째_재발급은_401이고_두_쿠키를_삭제한다() throws Exception {
		가입("user01");
		String old = 쿠키값(로그인("user01"), "refresh_token");
		assertThat(재발급(old).getStatus()).isEqualTo(200);
		MockHttpServletResponse second = 재발급(old);
		assertThat(second.getStatus()).isEqualTo(401);
		삭제쿠키_확인(second);
	}

	// ---------- 완료 조건 4: 없음/모름/빈값/만료 ----------

	@Test
	void refresh_쿠키가_없으면_401이고_두_쿠키를_삭제한다() throws Exception {
		MockHttpServletResponse res = 재발급(null);
		assertThat(res.getStatus()).isEqualTo(401);
		삭제쿠키_확인(res);
	}

	@Test
	void 모르는_값이면_401() throws Exception {
		MockHttpServletResponse res = 재발급("unknown-token-value");
		assertThat(res.getStatus()).isEqualTo(401);
		삭제쿠키_확인(res);
	}

	@Test
	void 빈_값이면_401() throws Exception {
		MockHttpServletResponse res = 재발급("");
		assertThat(res.getStatus()).isEqualTo(401);
		삭제쿠키_확인(res);
	}

	@Test
	void 없음_모름_빈값의_401_응답_본문이_모두_같다() throws Exception {
		String a = 재발급(null).getContentAsString();
		String b = 재발급("zzz").getContentAsString();
		String c = 재발급("").getContentAsString();
		assertThat(a).isEqualTo(b).isEqualTo(c);
	}

	@Test
	void Redis에서_만료된_토큰은_401() throws Exception {
		가입("user01");
		String token = 쿠키값(로그인("user01"), "refresh_token");
		// TTL을 1ms로 줄여 만료를 흉내 낸다
		redis.expire(해시키(token), Duration.ofMillis(1));
		Thread.sleep(300);
		assertThat(redis.hasKey(해시키(token))).isFalse();
		assertThat(재발급(token).getStatus()).isEqualTo(401);
	}

	// ---------- 완료 조건 5: CSRF ----------

	@Test
	void CSRF_없는_재발급은_403() throws Exception {
		가입("user01");
		String token = 쿠키값(로그인("user01"), "refresh_token");
		int status = mockMvc.perform(post("/auth/refresh").cookie(new Cookie("refresh_token", token))).andReturn()
				.getResponse().getStatus();
		assertThat(status).isEqualTo(403);
		// 거절됐으니 토큰은 소모되지 않아야 한다
		assertThat(redis.hasKey(해시키(token))).isTrue();
	}

	// ---------- 완료 조건 6: 나쁜 access_token 무시 ----------

	@Test
	void 만료된_access_token이_붙어도_재발급은_refresh로_동작한다() throws Exception {
		가입("user01");
		String token = 쿠키값(로그인("user01"), "refresh_token");
		Instant past = Instant.now().minusSeconds(3600);
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("username", "user01").issuedAt(past)
				.expiresAt(past.plusSeconds(900)).build();
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		int status = mockMvc.perform(post("/auth/refresh").with(csrf()).cookie(new Cookie("access_token", expired))
				.cookie(new Cookie("refresh_token", token))).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(200);
	}

	@Test
	void 변조된_access_token이_붙어도_재발급은_refresh로_동작한다() throws Exception {
		가입("user01");
		String token = 쿠키값(로그인("user01"), "refresh_token");
		int status = mockMvc.perform(post("/auth/refresh").with(csrf())
				.cookie(new Cookie("access_token", "aaa.bbb.ccc")).cookie(new Cookie("refresh_token", token)))
				.andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(200);
	}

	// ---------- 완료 조건 7: 삭제된 사용자 ----------

	@Test
	void DB에서_사용자가_삭제된_뒤_재발급은_401() throws Exception {
		가입("user01");
		String token = 쿠키값(로그인("user01"), "refresh_token");
		userRepository.deleteAll();
		assertThat(재발급(token).getStatus()).isEqualTo(401);
	}

	// ---------- 완료 조건 8: 여러 기기 ----------

	@Test
	void 두번_로그인하면_키가_2개고_하나를_로그아웃해도_다른_하나로_재발급된다() throws Exception {
		가입("user01");
		String t1 = 쿠키값(로그인("user01"), "refresh_token");
		String t2 = 쿠키값(로그인("user01"), "refresh_token");
		assertThat(t1).isNotEqualTo(t2);
		assertThat(키목록()).hasSize(2);
		mockMvc.perform(post("/auth/logout").with(csrf()).cookie(new Cookie("refresh_token", t1))).andReturn();
		assertThat(키목록()).hasSize(1);
		assertThat(재발급(t2).getStatus()).isEqualTo(200);
	}

	// ---------- 완료 조건 9: 로그아웃 ----------

	@Test
	void 로그아웃하면_204_Redis_키_삭제_두_쿠키_삭제_이후_재발급은_401() throws Exception {
		가입("user01");
		String token = 쿠키값(로그인("user01"), "refresh_token");
		MockHttpServletResponse res = mockMvc
				.perform(post("/auth/logout").with(csrf()).cookie(new Cookie("refresh_token", token))).andReturn()
				.getResponse();
		assertThat(res.getStatus()).isEqualTo(204);
		삭제쿠키_확인(res);
		assertThat(redis.hasKey(해시키(token))).isFalse();
		assertThat(재발급(token).getStatus()).isEqualTo(401);
	}

	@Test
	void 쿠키_없이_로그아웃해도_204() throws Exception {
		MockHttpServletResponse res = mockMvc.perform(post("/auth/logout").with(csrf())).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(204);
		삭제쿠키_확인(res);
	}

	// ---------- 완료 조건 10: 동시 재발급 경쟁 ----------

	@Test
	void 같은_refresh로_동시에_재발급하면_정확히_1개만_200이고_나머지는_401() throws Exception {
		가입("user01");
		String token = 쿠키값(로그인("user01"), "refresh_token");
		int n = 10;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			futures.add(pool.submit(() -> {
				ready.countDown();
				go.await(); // 모두 준비된 뒤 동시에 출발
				return 재발급(token).getStatus();
			}));
		}
		ready.await();
		go.countDown();
		int ok = 0, unauthorized = 0;
		for (Future<Integer> f : futures) {
			int s = f.get();
			if (s == 200) ok++;
			else if (s == 401) unauthorized++;
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(1);
		assertThat(unauthorized).isEqualTo(n - 1);
	}

	@Test
	void 서비스_consume을_동시에_호출해도_정확히_1번만_성공한다() throws Exception {
		String token = refreshTokenService.issue(42L);
		int n = 20;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Boolean>> futures = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			futures.add(pool.submit(() -> {
				go.await();
				return refreshTokenService.consume(token).isPresent();
			}));
		}
		go.countDown();
		int success = 0;
		for (Future<Boolean> f : futures) {
			if (f.get()) success++;
		}
		pool.shutdown();
		assertThat(success).isEqualTo(1);
	}
}
