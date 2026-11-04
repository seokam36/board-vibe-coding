package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.repository.UserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

// S4-b 상세 테스트(MockMvc): 로그인/로그아웃/내 정보의 경계 케이스를 assert로 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class LoginDetailTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	JwtEncoder jwtEncoder;

	private final ObjectMapper om = new ObjectMapper();

	@AfterEach
	void 정리() {
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private void 가입(String username, String password) throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json(username, password))).andReturn();
	}

	private String json(String username, String password) {
		return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
	}

	private MvcResult 로그인(String body) throws Exception {
		return mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn();
	}

	private String 로그인토큰(String username, String password) throws Exception {
		가입(username, password);
		MvcResult r = 로그인(json(username, password));
		return r.getResponse().getCookie("access_token").getValue();
	}

	private String 토큰(JwtEncoder enc, Instant iat, Instant exp) {
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("username", "user01")
				.issuedAt(iat).expiresAt(exp).build();
		return enc.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	private String 만료토큰() {
		Instant past = Instant.now().minusSeconds(3600);
		return 토큰(jwtEncoder, past, past.plusSeconds(900));
	}

	private int me상태(String token) throws Exception {
		return mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", token))).andReturn().getResponse()
				.getStatus();
	}

	private String b64(String s) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
	}

	// ---------- 완료 조건 1: 로그인 응답 ----------

	@Test
	void 로그인_응답은_id와_username만_담고_비밀번호는_없다() throws Exception {
		가입("user01", "password123");
		MvcResult r = 로그인(json("user01", "password123"));
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = om.readTree(r.getResponse().getContentAsString());
		assertThat(body.get("id").asLong()).isEqualTo(userRepository.findAll().get(0).getId());
		assertThat(body.get("username").asText()).isEqualTo("user01");
		assertThat(body.has("password")).isFalse();
		assertThat(r.getResponse().getContentAsString()).doesNotContain("password");
	}

	// ---------- 완료 조건 2: 다른 키 서명 ----------

	@Test
	void 다른_키로_서명한_토큰은_me가_401이고_올바른_키_토큰은_200() throws Exception {
		Instant now = Instant.now();
		// 서버와 다른 32바이트 키
		SecretKey otherKey = new SecretKeySpec("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8),
				"HmacSHA256");
		JwtEncoder other = NimbusJwtEncoder.withSecretKey(otherKey).build();
		assertThat(me상태(토큰(other, now, now.plusSeconds(900)))).isEqualTo(401);
		// 대조군: 같은 클레임을 서버 키로 서명하면 통과해야 한다
		assertThat(me상태(토큰(jwtEncoder, now, now.plusSeconds(900)))).isEqualTo(200);
	}

	// ---------- 완료 조건 3: /auth/me ----------

	@Test
	void 쿠키로_me를_조회하면_로그인한_사용자의_id와_username이_나온다() throws Exception {
		String token = 로그인토큰("user01", "password123");
		MockHttpServletResponse res = mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", token)))
				.andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode body = om.readTree(res.getContentAsString());
		assertThat(body.get("id").asLong()).isEqualTo(userRepository.findAll().get(0).getId());
		assertThat(body.get("username").asText()).isEqualTo("user01");
		assertThat(body.has("password")).isFalse();
	}

	@Test
	void 쿠키가_없으면_me는_401이고_리다이렉트_Location이_없다() throws Exception {
		MockHttpServletResponse res = mockMvc.perform(get("/auth/me")).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(401);
		assertThat(res.getHeader(HttpHeaders.LOCATION)).isNull();
	}

	@Test
	void Authorization_Bearer_헤더의_토큰은_무시되어_401() throws Exception {
		String token = 토큰(jwtEncoder, Instant.now(), Instant.now().plusSeconds(900));
		MockHttpServletResponse res = mockMvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
				.andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(401);
	}

	// ---------- 완료 조건 4: 만료·변조 ----------

	@Test
	void 서명이_변조된_토큰은_me가_401() throws Exception {
		String token = 토큰(jwtEncoder, Instant.now(), Instant.now().plusSeconds(900));
		String[] p = token.split("\\.");
		// 서명 부분을 다른 값으로 교체
		String badSig = new String(new char[p[2].length()]).replace('\0', 'A');
		assertThat(badSig).isNotEqualTo(p[2]);
		assertThat(me상태(p[0] + "." + p[1] + "." + badSig)).isEqualTo(401);
	}

	@Test
	void payload를_위조한_토큰은_me가_401() throws Exception {
		String token = 토큰(jwtEncoder, Instant.now(), Instant.now().plusSeconds(900));
		String[] p = token.split("\\.");
		// 서명은 그대로 두고 payload만 다른 사용자로 바꾼다
		long exp = Instant.now().plusSeconds(900).getEpochSecond();
		String forged = b64("{\"sub\":\"999\",\"username\":\"admin\",\"iat\":" + Instant.now().getEpochSecond()
				+ ",\"exp\":" + exp + "}");
		assertThat(me상태(p[0] + "." + forged + "." + p[2])).isEqualTo(401);
	}

	@Test
	void 만료된_토큰은_me가_401() throws Exception {
		assertThat(me상태(만료토큰())).isEqualTo(401);
	}

	// ---------- 완료 조건 5: 로그인 실패/검증 ----------

	@Test
	void 틀린_비밀번호와_없는_아이디는_401_본문이_같고_쿠키가_없다() throws Exception {
		가입("user01", "password123");
		MockHttpServletResponse wrongPw = 로그인(json("user01", "wrongpass1")).getResponse();
		MockHttpServletResponse noUser = 로그인(json("nouser99", "password123")).getResponse();
		assertThat(wrongPw.getStatus()).isEqualTo(401);
		assertThat(noUser.getStatus()).isEqualTo(401);
		assertThat(wrongPw.getContentAsString()).isEqualTo(noUser.getContentAsString());
		assertThat(wrongPw.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
		assertThat(noUser.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
	}

	@Test
	void 아이디만_빈값이면_400() throws Exception {
		assertThat(로그인(json("", "password123")).getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void 비밀번호만_빈값이면_400() throws Exception {
		assertThat(로그인(json("user01", "")).getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void 아이디와_비밀번호가_모두_빈값이면_400() throws Exception {
		assertThat(로그인(json("", "")).getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void 필드가_아예_없으면_400() throws Exception {
		assertThat(로그인("{}").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void CSRF_없는_로그인은_403() throws Exception {
		가입("user01", "password123");
		int status = mockMvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content(json("user01", "password123"))).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(403);
	}

	// ---------- 완료 조건 6: 로그아웃 ----------

	@Test
	void 로그아웃은_204이고_Max_Age_0으로_쿠키를_지운다() throws Exception {
		String token = 로그인토큰("user01", "password123");
		MockHttpServletResponse res = mockMvc
				.perform(post("/auth/logout").with(csrf()).cookie(new Cookie("access_token", token))).andReturn()
				.getResponse();
		assertThat(res.getStatus()).isEqualTo(204);
		assertThat(res.getHeader(HttpHeaders.SET_COOKIE)).contains("access_token=", "Max-Age=0");
	}

	@Test
	void CSRF_없는_로그아웃은_403() throws Exception {
		int status = mockMvc.perform(post("/auth/logout")).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(403);
	}

	@Test
	void 만료_쿠키가_붙어도_로그아웃은_204() throws Exception {
		int status = mockMvc.perform(post("/auth/logout").with(csrf()).cookie(new Cookie("access_token", 만료토큰())))
				.andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(204);
	}

	@Test
	void 로그인_안_한_상태의_로그아웃도_204() throws Exception {
		int status = mockMvc.perform(post("/auth/logout").with(csrf())).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(204);
	}

	// ---------- 완료 조건 7: 공개 경로 ----------

	@Test
	void 만료_쿠키가_붙어도_공개_경로는_모두_정상() throws Exception {
		Cookie expired = new Cookie("access_token", 만료토큰());
		assertThat(mockMvc.perform(get("/csrf").cookie(expired)).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(get("/posts").cookie(expired)).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		가입("user01", "password123");
		assertThat(mockMvc.perform(post("/auth/login").with(csrf()).cookie(expired)
				.contentType(MediaType.APPLICATION_JSON).content(json("user01", "password123"))).andReturn()
				.getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(post("/auth/signup").with(csrf()).cookie(expired)
				.contentType(MediaType.APPLICATION_JSON).content(json("user02", "password123"))).andReturn()
				.getResponse().getStatus()).isEqualTo(201);
	}

	@Test
	void 변조_쿠키가_붙어도_공개_경로는_모두_정상() throws Exception {
		Cookie tampered = new Cookie("access_token", 만료토큰() + "x");
		assertThat(mockMvc.perform(get("/csrf").cookie(tampered)).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		assertThat(mockMvc.perform(get("/posts").cookie(tampered)).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		가입("user01", "password123");
		assertThat(mockMvc.perform(post("/auth/login").with(csrf()).cookie(tampered)
				.contentType(MediaType.APPLICATION_JSON).content(json("user01", "password123"))).andReturn()
				.getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(post("/auth/signup").with(csrf()).cookie(tampered)
				.contentType(MediaType.APPLICATION_JSON).content(json("user02", "password123"))).andReturn()
				.getResponse().getStatus()).isEqualTo(201);
	}
}
