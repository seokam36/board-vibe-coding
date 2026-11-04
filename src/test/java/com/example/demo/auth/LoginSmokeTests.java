package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// S4-b 스모크 테스트: 로그인/로그아웃/내 정보 핵심 동작만 확인한다 (자세한 케이스는 tester가 추가)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class LoginSmokeTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PostRepository postRepository;
	@Autowired
	JwtDecoder jwtDecoder;
	@Autowired
	JwtEncoder jwtEncoder;

	@AfterEach
	void 정리() {
		// 글이 사용자를 참조(외래키)하므로 글부터 지운다
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private void 가입(String username, String password) throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
				.andExpect(status().isCreated());
	}

	private MvcResult 로그인(String username, String password) throws Exception {
		return mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")).andReturn();
	}

	private String 토큰만들기(Instant iat, Instant exp) {
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("username", "user01")
				.issuedAt(iat).expiresAt(exp).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
	}

	@Test
	void 로그인하면_쿠키가_발급되고_토큰으로_내정보를_조회한다() throws Exception {
		가입("user01", "password123");
		MvcResult result = 로그인("user01", "password123");
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).contains("access_token=", "HttpOnly", "Path=/", "SameSite=Lax", "Max-Age=900");
		assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();

		String token = result.getResponse().getCookie("access_token").getValue();
		Jwt jwt = jwtDecoder.decode(token);
		assertThat(jwt.getSubject()).isEqualTo(userRepository.findAll().get(0).getId().toString());
		assertThat(jwt.<String>getClaim("username")).isEqualTo("user01");
		assertThat(jwt.getExpiresAt().getEpochSecond() - jwt.getIssuedAt().getEpochSecond()).isEqualTo(900);

		mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", token)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("user01"));
	}

	@Test
	void 쿠키가_없거나_만료_변조_토큰이면_me는_401() throws Exception {
		mockMvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
		Instant past = Instant.now().minusSeconds(3600);
		mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", 토큰만들기(past, past.plusSeconds(900)))))
				.andExpect(status().isUnauthorized());
		String valid = 토큰만들기(Instant.now(), Instant.now().plusSeconds(900));
		mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", valid + "x")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void 틀린_비밀번호와_없는_아이디는_같은_401이고_빈값은_400() throws Exception {
		가입("user01", "password123");
		MvcResult wrongPw = 로그인("user01", "wrongpass1");
		MvcResult noUser = 로그인("nouser99", "password123");
		assertThat(wrongPw.getResponse().getStatus()).isEqualTo(401);
		assertThat(noUser.getResponse().getStatus()).isEqualTo(401);
		assertThat(wrongPw.getResponse().getContentAsString()).isEqualTo(noUser.getResponse().getContentAsString());
		assertThat(로그인("", "").getResponse().getStatus()).isEqualTo(400);
	}

	@Test
	void 로그아웃은_204와_쿠키_삭제() throws Exception {
		MvcResult result = mockMvc.perform(post("/auth/logout").with(csrf())).andExpect(status().isNoContent())
				.andReturn();
		assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("access_token=", "Max-Age=0");
	}

	@Test
	void 공개_경로는_만료_변조_쿠키가_붙어도_정상() throws Exception {
		Instant past = Instant.now().minusSeconds(3600);
		Cookie bad = new Cookie("access_token", 토큰만들기(past, past.plusSeconds(900)) + "x");
		mockMvc.perform(get("/posts").cookie(bad)).andExpect(status().isOk());
		mockMvc.perform(post("/auth/login").with(csrf()).cookie(bad).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"x\",\"password\":\"y\"}")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/auth/signup").with(csrf()).cookie(bad).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"user01\",\"password\":\"password123\"}")).andExpect(status().isCreated());
	}
}
