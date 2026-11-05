package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.RefreshTokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// S5-b 테스트: 회원별 리프레시 토큰 목록(user-refresh:{id} Set)과 revokeAll을 실제 Redis로 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class RefreshTokenRevokeAllTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PostRepository postRepository;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	RefreshTokenService refreshTokenService;

	// 테스트끼리 섞이지 않게 refresh:* 와 user-refresh:* 를 모두 비운다
	@BeforeEach
	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
		for (String pattern : new String[] { "refresh:*", "user-refresh:*" }) {
			Set<String> keys = redis.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}

	// ---------- 도우미 ----------

	private String json(String u) {
		return "{\"username\":\"" + u + "\",\"nickname\":\"n_" + u + "\",\"password\":\"password123\"}";
	}

	private void 가입(String u) throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(u)))
				.andReturn();
	}

	private String 로그인_토큰(String u) throws Exception {
		MockHttpServletResponse res = mockMvc
				.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json(u)))
				.andReturn().getResponse();
		return res.getCookie("refresh_token").getValue();
	}

	private int 재발급_상태(String refresh) throws Exception {
		return mockMvc.perform(post("/auth/refresh").with(csrf()).cookie(new Cookie("refresh_token", refresh)))
				.andReturn().getResponse().getStatus();
	}

	private MockHttpServletResponse 재발급(String refresh) throws Exception {
		return mockMvc.perform(post("/auth/refresh").with(csrf()).cookie(new Cookie("refresh_token", refresh)))
				.andReturn().getResponse();
	}

	private Long id(String username) {
		return userRepository.findAll().stream().filter(u -> u.getUsername().equals(username)).findFirst().get()
				.getId();
	}

	private String 해시(String token) throws Exception {
		byte[] h = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(h);
	}

	private String 집합키(Long userId) {
		return "user-refresh:" + userId;
	}

	private long 집합크기(Long userId) {
		Long size = redis.opsForSet().size(집합키(userId));
		return size == null ? 0 : size;
	}

	// ---------- 완료 조건 1: revokeAll 하면 그 회원의 모든 토큰이 무효 ----------

	@Test
	void 같은_회원의_토큰_두개는_revokeAll_뒤_재발급이_모두_401이다() throws Exception {
		가입("user01");
		String t1 = 로그인_토큰("user01");
		String t2 = 로그인_토큰("user01");
		assertThat(집합크기(id("user01"))).isEqualTo(2);
		refreshTokenService.revokeAll(id("user01"));
		assertThat(재발급_상태(t1)).isEqualTo(401);
		assertThat(재발급_상태(t2)).isEqualTo(401);
	}

	// ---------- 완료 조건 2: 다른 회원은 영향 없음 ----------

	@Test
	void 다른_회원의_토큰은_revokeAll_뒤에도_재발급이_200이다() throws Exception {
		가입("user01");
		가입("user02");
		로그인_토큰("user01");
		String other = 로그인_토큰("user02");
		refreshTokenService.revokeAll(id("user01"));
		assertThat(재발급_상태(other)).isEqualTo(200);
	}

	// ---------- 완료 조건 3: 회전해도 Set 크기 유지 ----------

	@Test
	void 재발급_회전_후에도_회원_Set_크기가_그대로이고_옛_해시는_빠지고_새_해시가_들어간다() throws Exception {
		가입("user01");
		String old = 로그인_토큰("user01");
		Long uid = id("user01");
		long before = 집합크기(uid);
		MockHttpServletResponse res = 재발급(old);
		assertThat(res.getStatus()).isEqualTo(200);
		String fresh = res.getCookie("refresh_token").getValue();
		assertThat(집합크기(uid)).isEqualTo(before);
		assertThat(redis.opsForSet().isMember(집합키(uid), 해시(old))).isFalse();
		assertThat(redis.opsForSet().isMember(집합키(uid), 해시(fresh))).isTrue();
	}

	// ---------- 완료 조건 4: 로그아웃하면 Set에서 빠짐 ----------

	@Test
	void 로그아웃하면_그_토큰_해시가_Set에서_빠지고_크기가_1_줄어든다() throws Exception {
		가입("user01");
		String t1 = 로그인_토큰("user01");
		String t2 = 로그인_토큰("user01");
		Long uid = id("user01");
		long before = 집합크기(uid);
		mockMvc.perform(post("/auth/logout").with(csrf()).cookie(new Cookie("refresh_token", t1))).andReturn();
		assertThat(집합크기(uid)).isEqualTo(before - 1);
		assertThat(redis.opsForSet().isMember(집합키(uid), 해시(t1))).isFalse();
		assertThat(redis.opsForSet().isMember(집합키(uid), 해시(t2))).isTrue();
	}

	// ---------- 완료 조건 5: Set TTL ----------

	@Test
	void 회원_Set_키에_TTL이_있고_0초_초과_7일_이하이다() throws Exception {
		가입("user01");
		로그인_토큰("user01");
		Long ttl = redis.getExpire(집합키(id("user01")));
		assertThat(ttl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(604800L);
	}

	// 5-a: Set가 먼저 만료되면 아직 살아 있는 토큰이 목록에서 빠지므로, Set TTL >= 각 토큰 키 TTL 이어야 한다
	@Test
	void 회원_Set_TTL은_각_refresh_키_TTL_이상이다() throws Exception {
		가입("user01");
		String t1 = 로그인_토큰("user01");
		String t2 = 로그인_토큰("user01");
		Long setTtl = redis.getExpire(집합키(id("user01")));
		for (String t : new String[] { t1, t2 }) {
			Long keyTtl = redis.getExpire("refresh:" + 해시(t));
			assertThat(setTtl).isGreaterThanOrEqualTo(keyTtl);
		}
	}

	// 5-b: Set TTL이 짧아져 있으면 새 로그인 때 다시 약 7일로 늘어난다
	@Test
	void 짧아진_Set_TTL은_새_로그인에서_약_7일로_늘어난다() throws Exception {
		가입("user01");
		로그인_토큰("user01");
		Long uid = id("user01");
		redis.expire(집합키(uid), java.time.Duration.ofSeconds(60));
		assertThat(redis.getExpire(집합키(uid))).isLessThanOrEqualTo(60L);
		로그인_토큰("user01");
		assertThat(redis.getExpire(집합키(uid))).isBetween(604800L - 60, 604800L);
	}

	// 5-c: Set TTL이 7일보다 길면 짧은 값으로 덮어쓰지 않는다
	@Test
	void 칠일보다_긴_Set_TTL은_새_로그인에서_줄어들지_않는다() throws Exception {
		가입("user01");
		로그인_토큰("user01");
		Long uid = id("user01");
		long eightDays = 8L * 24 * 3600;
		redis.expire(집합키(uid), java.time.Duration.ofSeconds(eightDays));
		로그인_토큰("user01");
		assertThat(redis.getExpire(집합키(uid))).isGreaterThan(604800L + 3600);
	}

	// ---------- 경계 경우 ----------

	@Test
	void 토큰이_없는_회원에_revokeAll해도_예외가_없고_다른_회원은_영향없다() throws Exception {
		가입("user01");
		가입("user02");
		String other = 로그인_토큰("user02");
		Long emptyUser = id("user01"); // 로그인한 적 없어 Set 없음
		assertThat(redis.hasKey(집합키(emptyUser))).isFalse();
		assertThatCode(() -> refreshTokenService.revokeAll(emptyUser)).doesNotThrowAnyException();
		assertThat(재발급_상태(other)).isEqualTo(200);
	}

	@Test
	void 회원_Set_원소에는_토큰_원문이_없고_해시_hex만_들어있다() throws Exception {
		가입("user01");
		String t1 = 로그인_토큰("user01");
		String t2 = 로그인_토큰("user01");
		Set<String> members = redis.opsForSet().members(집합키(id("user01")));
		assertThat(members).hasSize(2).doesNotContain(t1, t2);
		for (String m : members) {
			assertThat(m).matches("[0-9a-f]{64}");
		}
		assertThat(members).containsExactlyInAnyOrder(해시(t1), 해시(t2));
	}

	// ---------- 완료 조건 6: 만료된 키 해시가 Set에 남아 있어도 안전 ----------

	@Test
	void Set에_이미_삭제된_refresh_키의_해시가_남아도_revokeAll은_예외없이_Set까지_지운다() throws Exception {
		가입("user01");
		String live = 로그인_토큰("user01");
		Long uid = id("user01");
		// 존재하지 않는 refresh 키의 해시를 Set에 직접 넣어 "만료되어 키만 사라진" 상황을 만든다
		redis.opsForSet().add(집합키(uid), "deadbeef".repeat(8));
		assertThat(집합크기(uid)).isEqualTo(2);
		assertThatCode(() -> refreshTokenService.revokeAll(uid)).doesNotThrowAnyException();
		assertThat(redis.hasKey(집합키(uid))).isFalse();
		assertThat(재발급_상태(live)).isEqualTo(401);
	}

	// ---------- 완료 조건 7: revokeAll 후 다시 로그인 ----------

	@Test
	void revokeAll_뒤_다시_로그인하면_새_토큰으로_재발급이_200이다() throws Exception {
		가입("user01");
		String old = 로그인_토큰("user01");
		Long uid = id("user01");
		refreshTokenService.revokeAll(uid);
		String fresh = 로그인_토큰("user01");
		assertThat(fresh).isNotEqualTo(old);
		assertThat(집합크기(uid)).isEqualTo(1);
		assertThat(재발급_상태(fresh)).isEqualTo(200);
	}
}
