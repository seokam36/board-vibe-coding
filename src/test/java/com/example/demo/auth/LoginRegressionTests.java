package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;

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
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// S4-b review 지적 수정 회귀 테스트(MockMvc). 쿠키 로그인 구조에서 CSRF/오류 처리/기본 필터가 의도대로인지 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class LoginRegressionTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PostRepository postRepository;
	@Autowired
	JwtEncoder jwtEncoder;

	@AfterEach
	void 정리() {
		// 글이 사용자를 참조(외래키)하므로 글부터 지운다
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private String json(String username, String password) {
		return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}";
	}

	private String 유효토큰() {
		Instant now = Instant.now();
		return 토큰(now, now.plusSeconds(900));
	}

	private String 토큰(Instant iat, Instant exp) {
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("username", "user01").issuedAt(iat)
				.expiresAt(exp).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	// 1. 쿠키 인증이어도 CSRF는 면제되지 않는다 (CSRF 없으면 403, 있으면 CSRF를 통과해 다음 단계로 간다)
	@Test
	void 유효한_쿠키가_있어도_CSRF_토큰_없는_POST는_403이다() throws Exception {
		Cookie cookie = new Cookie("access_token", 유효토큰());
		int without = mockMvc.perform(post("/auth/me").cookie(cookie)).andReturn().getResponse().getStatus();
		assertThat(without).isEqualTo(403);
		// 대조군: CSRF를 붙이면 403이 아니다 (/auth/me는 GET만 있어서 405)
		int with = mockMvc.perform(post("/auth/me").with(csrf()).cookie(cookie)).andReturn().getResponse().getStatus();
		assertThat(with).isNotEqualTo(403);
		assertThat(with).isEqualTo(405);
	}

	// 2. bcrypt 72바이트 초과 비밀번호는 500이 아니라 401
	@Test
	void 비밀번호가_72바이트를_넘으면_로그인은_500이_아니라_401이다() throws Exception {
		String longAscii = "a".repeat(100);
		String longKorean = "가".repeat(30); // 한글 1자 = 3바이트 → 90바이트
		for (String pw : new String[] { longAscii, longKorean }) {
			MockHttpServletResponse res = mockMvc.perform(post("/auth/login").with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content(json("user01", pw))).andReturn().getResponse();
			assertThat(res.getStatus()).as("비밀번호 길이 %d", pw.length()).isEqualTo(401);
		}
	}

	// 4. 토큰이 잘못돼도 401 응답에 실패 이유를 노출하지 않는다
	@Test
	void 만료_변조_토큰의_401은_WWW_Authenticate에_실패_이유가_없다() throws Exception {
		String expired = 토큰(Instant.now().minusSeconds(3600), Instant.now().minusSeconds(2700));
		String tampered = 유효토큰() + "x";
		for (String t : new String[] { expired, tampered }) {
			MockHttpServletResponse res = mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", t)))
					.andReturn().getResponse();
			assertThat(res.getStatus()).isEqualTo(401);
			String header = res.getHeader(HttpHeaders.WWW_AUTHENTICATE);
			if (header != null) {
				assertThat(header).doesNotContain("error_description").doesNotContain("invalid_token")
						.doesNotContain("expired").doesNotContain("Jwt");
			}
			assertThat(res.getContentAsString()).doesNotContain("expired").doesNotContain("Jwt");
		}
	}

	// 5. 기본 Spring 로그아웃(POST /logout → 302)이 꺼져 있다
	@Test
	void 기본_logout_경로는_302_리다이렉트가_아니다() throws Exception {
		for (boolean withCsrf : new boolean[] { true, false }) {
			var req = post("/logout");
			if (withCsrf) {
				req = req.with(csrf());
			}
			MockHttpServletResponse res = mockMvc.perform(req).andReturn().getResponse();
			assertThat(res.getStatus()).isNotEqualTo(302);
			assertThat(res.getHeader(HttpHeaders.LOCATION)).isNull();
		}
	}

	// 3(기본값). COOKIE_SECURE 미설정이면 Secure 속성이 없다
	@Test
	void 기본_설정에서는_로그인_쿠키에_Secure가_없다() throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json("user01", "password123"))).andReturn();
		MockHttpServletResponse res = mockMvc.perform(post("/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content(json("user01", "password123"))).andReturn()
				.getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		String setCookie = res.getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).startsWith("access_token=").contains("HttpOnly").doesNotContain("Secure");
	}
}
