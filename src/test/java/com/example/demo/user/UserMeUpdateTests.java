package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import org.springframework.test.web.servlet.ResultActions;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

// S5-c 테스트: 닉네임 변경(PUT /users/me/nickname)과 비밀번호 변경(PUT /users/me/password)의 완료 조건을 검사한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class UserMeUpdateTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PostRepository postRepository;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	JwtEncoder jwtEncoder;

	private final ObjectMapper om = new ObjectMapper();

	// 테스트끼리 섞이지 않게 DB와 Redis(refresh:*, user-refresh:*)를 앞뒤로 비운다
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

	private static final String PW = "password123";

	private void 가입(String username, String nickname) throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"nickname\":\"" + nickname + "\",\"password\":\"" + PW
						+ "\"}"))
				.andExpect(status().isCreated());
	}

	private MockHttpServletResponse 로그인(String username, String password) throws Exception {
		return mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn()
				.getResponse();
	}

	private Cookie 액세스(MockHttpServletResponse login) {
		return new Cookie("access_token", login.getCookie("access_token").getValue());
	}

	private int 재발급_상태(String refresh) throws Exception {
		return mockMvc.perform(post("/auth/refresh").with(csrf()).cookie(new Cookie("refresh_token", refresh)))
				.andReturn().getResponse().getStatus();
	}

	private ResultActions 닉네임변경(Cookie access, String bodyJson) throws Exception {
		return mockMvc.perform(put("/users/me/nickname").with(csrf()).cookie(access)
				.contentType(MediaType.APPLICATION_JSON).content(bodyJson));
	}

	private ResultActions 닉네임변경값(Cookie access, String nickname) throws Exception {
		return 닉네임변경(access, "{\"nickname\":\"" + nickname + "\"}");
	}

	private ResultActions 비번변경(Cookie access, String bodyJson) throws Exception {
		return mockMvc.perform(put("/users/me/password").with(csrf()).cookie(access)
				.contentType(MediaType.APPLICATION_JSON).content(bodyJson));
	}

	private ResultActions 비번변경값(Cookie access, String current, String next) throws Exception {
		return 비번변경(access, "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}");
	}

	private String 닉네임_DB(String username) {
		return userRepository.findByUsername(username).orElseThrow().getNickname();
	}

	private String 비번_DB(String username) {
		return userRepository.findByUsername(username).orElseThrow().getPassword();
	}

	// 서명이 맞는 정상 토큰인데 그 회원 행은 DB에 없는 상황을 만든다
	private Cookie 행없는_회원_토큰() {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("999999999").claim("username", "ghost").issuedAt(now)
				.expiresAt(now.plusSeconds(900)).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new Cookie("access_token", token);
	}

	// ============ 닉네임 변경 ============

	// 조건 1
	@Test
	void 조건1_닉네임_변경은_200과_새값을_주고_me와_글_작성자_닉네임도_새값이다() throws Exception {
		가입("user01", "옛닉네임");
		MockHttpServletResponse login = 로그인("user01", PW);
		Long uid = userRepository.findByUsername("user01").orElseThrow().getId();
		Post post = postRepository.save(new Post("제목", "내용", userRepository.findById(uid).orElseThrow(), null, PostCategory.CHAT));

		닉네임변경값(액세스(login), "새닉네임").andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(uid))
				.andExpect(jsonPath("$.username").value("user01"))
				.andExpect(jsonPath("$.nickname").value("새닉네임"))
				.andExpect(jsonPath("$.password").doesNotExist());

		mockMvc.perform(get("/auth/me").cookie(액세스(login))).andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("새닉네임"));
		mockMvc.perform(get("/posts/" + post.getId())).andExpect(status().isOk())
				.andExpect(jsonPath("$.author.nickname").value("새닉네임"));
	}

	// 조건 2
	@Test
	void 조건2_닉네임_변경_뒤_재발급_응답의_nickname도_새값이다() throws Exception {
		가입("user01", "옛닉네임");
		MockHttpServletResponse login = 로그인("user01", PW);
		닉네임변경값(액세스(login), "새닉네임").andExpect(status().isOk());

		MockHttpServletResponse refresh = mockMvc.perform(post("/auth/refresh").with(csrf())
				.cookie(new Cookie("refresh_token", login.getCookie("refresh_token").getValue()))).andReturn()
				.getResponse();
		assertThat(refresh.getStatus()).isEqualTo(200);
		assertThat(om.readTree(refresh.getContentAsString()).get("nickname").asString()).isEqualTo("새닉네임");
	}

	// 조건 3
	@ParameterizedTest
	@ValueSource(strings = { "a", "012345678901234567890", "홍 길동", "kim!", "홍길동#", "kim-lee", "", " " })
	void 조건3_규칙_위반_닉네임은_400이고_닉네임이_바뀌지_않는다(String nickname) throws Exception {
		가입("user01", "옛닉네임");
		MockHttpServletResponse login = 로그인("user01", PW);
		닉네임변경값(액세스(login), nickname).andExpect(status().isBadRequest());
		assertThat(닉네임_DB("user01")).isEqualTo("옛닉네임");
	}

	@Test
	void 조건3_nickname_필드가_없거나_null이면_400이고_닉네임이_바뀌지_않는다() throws Exception {
		가입("user01", "옛닉네임");
		MockHttpServletResponse login = 로그인("user01", PW);
		닉네임변경(액세스(login), "{}").andExpect(status().isBadRequest());
		닉네임변경(액세스(login), "{\"nickname\":null}").andExpect(status().isBadRequest());
		assertThat(닉네임_DB("user01")).isEqualTo("옛닉네임");
	}

	@ParameterizedTest
	@ValueSource(strings = { "ab", "01234567890123456789" })
	void 조건3_경계값_닉네임은_200(String nickname) throws Exception {
		가입("user01", "옛닉네임");
		MockHttpServletResponse login = 로그인("user01", PW);
		닉네임변경값(액세스(login), nickname).andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value(nickname));
	}

	// 조건 4
	@Test
	void 조건4_다른_사람이_쓰는_닉네임이면_409이고_안_바뀐다() throws Exception {
		가입("user01", "닉네임일");
		가입("user02", "닉네임이");
		MockHttpServletResponse login = 로그인("user02", PW);
		닉네임변경값(액세스(login), "닉네임일").andExpect(status().isConflict());
		assertThat(닉네임_DB("user02")).isEqualTo("닉네임이");
		assertThat(닉네임_DB("user01")).isEqualTo("닉네임일");
	}

	@Test
	void 조건4_자기_현재_닉네임_그대로_보내면_200이다() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		닉네임변경값(액세스(login), "닉네임일").andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("닉네임일"));
	}

	// 조건 5
	@Test
	void 조건5_닉네임_변경_로그인_쿠키가_없으면_401() throws Exception {
		mockMvc.perform(put("/users/me/nickname").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"새닉네임\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void 조건5_닉네임_변경_CSRF_헤더가_없으면_403이고_안_바뀐다() throws Exception {
		가입("user01", "옛닉네임");
		MockHttpServletResponse login = 로그인("user01", PW);
		mockMvc.perform(put("/users/me/nickname").cookie(액세스(login)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"nickname\":\"새닉네임\"}")).andExpect(status().isForbidden());
		assertThat(닉네임_DB("user01")).isEqualTo("옛닉네임");
	}

	@Test
	void 조건5_닉네임_변경_토큰은_유효하지만_회원_행이_없으면_401() throws Exception {
		닉네임변경값(행없는_회원_토큰(), "새닉네임").andExpect(status().isUnauthorized());
	}

	// 조건 6
	@Test
	void 조건6_두_회원이_동시에_같은_새_닉네임으로_바꾸면_한_명만_200이고_나머지는_409() throws Exception {
		가입("user01", "닉네임일");
		가입("user02", "닉네임이");
		List<Cookie> cookies = List.of(액세스(로그인("user01", PW)), 액세스(로그인("user02", PW)));
		int n = cookies.size();
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (Cookie c : cookies) {
			results.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return 닉네임변경값(c, "겹치는닉네임").andReturn().getResponse().getStatus();
			}));
		}
		ready.await();
		go.countDown();
		int ok = 0, conflict = 0;
		for (Future<Integer> f : results) {
			int s = f.get();
			if (s == 200) ok++;
			else if (s == 409) conflict++;
		}
		pool.shutdown();
		assertThat(ok).isEqualTo(1);
		assertThat(conflict).isEqualTo(n - 1);
		assertThat(userRepository.findAll().stream().filter(u -> u.getNickname().equals("겹치는닉네임")).count()).isEqualTo(1);
	}

	// ============ 비밀번호 변경 ============

	// 조건 7
	@Test
	void 조건7_비밀번호_변경은_204_본문없음_두_쿠키_삭제_Set_Cookie를_준다() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		MockHttpServletResponse res = 비번변경값(액세스(login), PW, "newPassw0rd").andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(204);
		assertThat(res.getContentAsString()).isEmpty();
		List<String> setCookies = res.getHeaders(HttpHeaders.SET_COOKIE);
		assertThat(setCookies).anyMatch(c -> c.startsWith("access_token=;") && c.contains("Max-Age=0"));
		assertThat(setCookies).anyMatch(c -> c.startsWith("refresh_token=;") && c.contains("Max-Age=0"));
	}

	// 조건 8
	@Test
	void 조건8_변경_뒤_새_비밀번호로_로그인_200이고_옛_비밀번호는_401() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		비번변경값(액세스(login), PW, "newPassw0rd").andExpect(status().isNoContent());
		assertThat(로그인("user01", "newPassw0rd").getStatus()).isEqualTo(200);
		assertThat(로그인("user01", PW).getStatus()).isEqualTo(401);
	}

	// 조건 9
	@Test
	void 조건9_변경_전_받은_refresh_토큰_둘은_401이고_다른_회원_토큰은_200() throws Exception {
		가입("user01", "닉네임일");
		가입("user02", "닉네임이");
		MockHttpServletResponse login1 = 로그인("user01", PW);
		String r1 = login1.getCookie("refresh_token").getValue();
		String r2 = 로그인("user01", PW).getCookie("refresh_token").getValue();
		String other = 로그인("user02", PW).getCookie("refresh_token").getValue();
		비번변경값(액세스(login1), PW, "newPassw0rd").andExpect(status().isNoContent());
		assertThat(재발급_상태(r1)).isEqualTo(401);
		assertThat(재발급_상태(r2)).isEqualTo(401);
		assertThat(재발급_상태(other)).isEqualTo(200);
	}

	// 조건 10
	@Test
	void 조건10_현재_비밀번호가_틀리면_400이고_비번과_refresh_토큰은_그대로다() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		String refresh = login.getCookie("refresh_token").getValue();
		String before = 비번_DB("user01");
		비번변경값(액세스(login), "wrongPassw0rd", "newPassw0rd").andExpect(status().isBadRequest());
		assertThat(비번_DB("user01")).isEqualTo(before);
		assertThat(로그인("user01", PW).getStatus()).isEqualTo(200);
		assertThat(재발급_상태(refresh)).isEqualTo(200);
	}

	// 조건 11
	@Test
	void 조건11_새_비밀번호가_현재와_같으면_400이고_안_바뀐다() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		String before = 비번_DB("user01");
		비번변경값(액세스(login), PW, PW).andExpect(status().isBadRequest());
		assertThat(비번_DB("user01")).isEqualTo(before);
	}

	@ParameterizedTest
	@ValueSource(strings = { "1234567", "        ", "" })
	void 조건11_짧거나_공백만이거나_빈_새_비밀번호는_400(String next) throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		String before = 비번_DB("user01");
		비번변경값(액세스(login), PW, next).andExpect(status().isBadRequest());
		assertThat(비번_DB("user01")).isEqualTo(before);
	}

	@Test
	void 조건11_새_비밀번호_65자는_400() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		비번변경값(액세스(login), PW, "a".repeat(65)).andExpect(status().isBadRequest());
	}

	@Test
	void 조건11_새_비밀번호가_UTF8_73바이트_이상이면_400이고_72바이트는_204() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		// '가'는 3바이트: 25자 = 75바이트(64자 이하지만 72바이트 초과)
		비번변경값(액세스(login), PW, "가".repeat(25)).andExpect(status().isBadRequest());
		// 24자 = 정확히 72바이트는 허용
		비번변경값(액세스(login), PW, "가".repeat(24)).andExpect(status().isNoContent());
	}

	@Test
	void 조건11_필드가_없거나_null이면_400() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		비번변경(액세스(login), "{\"currentPassword\":\"" + PW + "\"}").andExpect(status().isBadRequest());
		비번변경(액세스(login), "{\"newPassword\":\"newPassw0rd\"}").andExpect(status().isBadRequest());
		비번변경(액세스(login), "{}").andExpect(status().isBadRequest());
		비번변경(액세스(login), "{\"currentPassword\":null,\"newPassword\":null}").andExpect(status().isBadRequest());
		비번변경값(액세스(login), "", "newPassw0rd").andExpect(status().isBadRequest());
		assertThat(로그인("user01", PW).getStatus()).isEqualTo(200);
	}

	// 조건 12
	@Test
	void 조건12_비밀번호_변경_로그인_쿠키가_없으면_401() throws Exception {
		mockMvc.perform(put("/users/me/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"newPassw0rd\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 조건12_비밀번호_변경_CSRF_헤더가_없으면_403이고_안_바뀐다() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		mockMvc.perform(put("/users/me/password").cookie(액세스(login)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"newPassw0rd\"}"))
				.andExpect(status().isForbidden());
		assertThat(로그인("user01", PW).getStatus()).isEqualTo(200);
	}

	@Test
	void 조건12_비밀번호_변경_토큰은_유효하지만_회원_행이_없으면_401() throws Exception {
		비번변경값(행없는_회원_토큰(), PW, "newPassw0rd").andExpect(status().isUnauthorized());
	}

	// 조건 13
	@Test
	void 조건13_DB_비밀번호는_bcrypt로_시작하고_응답_본문과_헤더에_비밀번호가_없다() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		MockHttpServletResponse res = 비번변경값(액세스(login), PW, "newPassw0rd").andReturn().getResponse();
		assertThat(비번_DB("user01")).startsWith("{bcrypt}");
		assertThat(res.getContentAsString()).doesNotContain("newPassw0rd").doesNotContain(PW);
		// 닉네임 변경 응답에도 비밀번호가 없다 (새로 로그인해서 확인)
		MockHttpServletResponse login2 = 로그인("user01", "newPassw0rd");
		String body = 닉네임변경값(액세스(login2), "새닉네임").andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertThat(body.toLowerCase()).doesNotContain("password");
	}

	// ============ S5-c review 보강: 한 컬럼만 UPDATE (lost update 방지) ============

	// 보강 1: 비밀번호 UPDATE 뒤에 닉네임 UPDATE가 나가도 비밀번호가 옛 값으로 돌아가지 않는다
	@Test
	void 보강1_비밀번호가_바뀐_뒤_닉네임_UPDATE가_나가도_비밀번호는_새_해시_그대로다() throws Exception {
		가입("user01", "옛닉네임");
		Long id = userRepository.findByUsername("user01").orElseThrow().getId();
		// 비밀번호 UPDATE 다음 닉네임 UPDATE: 닉네임 쿼리가 비밀번호 칸을 건드리지 않는지 본다
		assertThat(userRepository.updatePassword(id, "{noop}new-hash")).isEqualTo(1);
		assertThat(userRepository.updateNickname(id, "새닉네임")).isEqualTo(1);
		assertThat(비번_DB("user01")).isEqualTo("{noop}new-hash");
		assertThat(닉네임_DB("user01")).isEqualTo("새닉네임");
	}

	// 보강 2: 반대 순서(닉네임 먼저, 비밀번호 나중)에도 닉네임이 옛 값으로 돌아가지 않는다
	@Test
	void 보강2_닉네임이_바뀐_뒤_비밀번호_UPDATE가_나가도_닉네임은_새값_그대로다() throws Exception {
		가입("user01", "옛닉네임");
		Long id = userRepository.findByUsername("user01").orElseThrow().getId();
		assertThat(userRepository.updateNickname(id, "새닉네임")).isEqualTo(1);
		assertThat(userRepository.updatePassword(id, "{noop}new-hash")).isEqualTo(1);
		assertThat(닉네임_DB("user01")).isEqualTo("새닉네임");
		assertThat(비번_DB("user01")).isEqualTo("{noop}new-hash");
	}

	// 보강 3: 실제 HTTP로 닉네임 변경과 비밀번호 변경을 동시에 여러 번 보내도 둘 다 반영된다
	@Test
	void 보강3_닉네임_변경과_비밀번호_변경을_동시에_반복해도_둘_다_최종_반영된다() throws Exception {
		for (int round = 0; round < 5; round++) {
			정리();
			가입("user01", "옛닉네임");
			Cookie access = 액세스(로그인("user01", PW));
			ExecutorService pool = Executors.newFixedThreadPool(2);
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch go = new CountDownLatch(1);
			Future<Integer> nick = pool.submit(() -> {
				ready.countDown();
				go.await();
				return 닉네임변경값(access, "새닉네임").andReturn().getResponse().getStatus();
			});
			Future<Integer> pw = pool.submit(() -> {
				ready.countDown();
				go.await();
				return 비번변경값(access, PW, "newPassw0rd").andReturn().getResponse().getStatus();
			});
			ready.await();
			go.countDown();
			assertThat(nick.get()).isEqualTo(200);
			assertThat(pw.get()).isEqualTo(204);
			pool.shutdown();
			assertThat(로그인("user01", "newPassw0rd").getStatus()).isEqualTo(200);
			assertThat(로그인("user01", PW).getStatus()).isEqualTo(401);
			assertThat(닉네임_DB("user01")).isEqualTo("새닉네임");
		}
	}

	// 보강 4: currentPassword가 너무 길면(65자, 75바이트) 400이고 비밀번호는 그대로
	@ParameterizedTest
	@ValueSource(strings = { "a65", "가25" })
	void 보강4_너무_긴_currentPassword는_400이고_비밀번호는_그대로다(String kind) throws Exception {
		String tooLong = kind.equals("a65") ? "a".repeat(65) : "가".repeat(25);
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01", PW);
		String before = 비번_DB("user01");
		비번변경값(액세스(login), tooLong, "newPassw0rd").andExpect(status().isBadRequest());
		assertThat(비번_DB("user01")).isEqualTo(before);
		assertThat(로그인("user01", PW).getStatus()).isEqualTo(200);
	}

	// 보강 5: 없는 id면 영향 행이 0 (서비스는 이를 401로 바꾼다)
	@Test
	void 보강5_없는_id에_updateNickname_updatePassword하면_영향_행_수가_0이다() {
		assertThat(userRepository.updateNickname(999999999L, "아무닉네임")).isZero();
		assertThat(userRepository.updatePassword(999999999L, "{noop}x")).isZero();
	}
}
