package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

// S4-a 보강 테스트: 입력 검증 경계값, 응답 형태, 거부 시 DB 불변, 보안 접근 규칙을 확인한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SignupValidationTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	UserRepository userRepository;
	@Autowired
	PostRepository postRepository;

	@AfterEach
	void 정리() {
		// 글이 사용자를 참조(외래키)하므로 글부터 지운다
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private ResultActions 가입(String username, String password) throws Exception {
		return mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
	}

	// 조건 1: 응답 필드가 id, username 두 개뿐이다
	@Test
	void 가입_응답은_id와_username_두_필드만_가진다() throws Exception {
		가입("user01", "password123")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.username").value("user01"));
	}

	// 조건 4: 잘못된 아이디는 400이고 DB에 저장되지 않는다
	@ParameterizedTest
	@ValueSource(strings = { "", "abc", "abcdefghijklmnopqrstu", "User01", "user 01", "유저01" })
	void 잘못된_username은_400이고_저장되지_않는다(String username) throws Exception {
		가입(username, "password123").andExpect(status().isBadRequest());
		assertThat(userRepository.count()).isZero();
	}

	// 조건 4: 잘못된 비밀번호는 400 (빈 값, 7자, 65자, 한글 25자=75바이트)
	@ParameterizedTest
	@ValueSource(strings = { "", "1234567", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"가가가가가가가가가가가가가가가가가가가가가가가가가" })
	void 잘못된_password는_400이고_저장되지_않는다(String password) throws Exception {
		가입("user01", password).andExpect(status().isBadRequest());
		assertThat(userRepository.count()).isZero();
	}

	// 조건 4: JSON 형식이 깨지면 400
	@Test
	void JSON_형식_오류는_400이다() throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"user01\","))
				.andExpect(status().isBadRequest());
		assertThat(userRepository.count()).isZero();
	}

	// 조건 4: 필드 자체가 빠진 경우(null)도 400
	@Test
	void 필드가_빠진_요청은_400이다() throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	// 조건 4: 경계 통과값
	@ParameterizedTest
	@ValueSource(strings = { "abcd", "abcdefghijklmnopqrst", "a_1_b_2" })
	void 경계_username은_201이다(String username) throws Exception {
		가입(username, "password123").andExpect(status().isCreated());
		assertThat(userRepository.count()).isEqualTo(1);
	}

	@ParameterizedTest
	@ValueSource(strings = { "12345678", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
			"가가가가가가가가가가가가가가가가가가가가가가가가" })
	void 경계_password_8자_64자_한글24자는_201이다(String password) throws Exception {
		가입("user01", password).andExpect(status().isCreated());
	}

	// 조건 3: 409 시 행 수가 그대로
	@Test
	void 중복_가입_409_후에도_행_수는_1이다() throws Exception {
		가입("user01", "password123").andExpect(status().isCreated());
		가입("user01", "password123").andExpect(status().isConflict());
		assertThat(userRepository.count()).isEqualTo(1);
	}

	// 조건 5: CSRF 없으면 403이고 저장되지 않는다
	@Test
	void CSRF_없는_가입은_403이고_행이_늘지_않는다() throws Exception {
		mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"user01\",\"password\":\"password123\"}"))
				.andExpect(status().isForbidden());
		assertThat(userRepository.count()).isZero();
	}

	// 조건 6: 로그인 없이 읽기 API는 200이고 세션 쿠키가 없다
	@Test
	void 로그인_없이_글_목록과_헬스체크는_200이고_JSESSIONID_쿠키가_없다() throws Exception {
		for (String path : new String[] { "/posts", "/actuator/health" }) {
			var r = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse();
			assertThat(r.getCookie("JSESSIONID")).as(path).isNull();
		}
	}

	// 조건 6: /csrf는 200이고 세션 쿠키를 만들지 않는다 (XSRF-TOKEN 쿠키는 MockMvc가 재현 못해 CsrfRealServerTests에서 실제 서버로 확인)
	@Test
	void csrf_호출시_200이고_JSESSIONID는_없다() throws Exception {
		var r = mockMvc.perform(get("/csrf")).andExpect(status().isOk()).andReturn().getResponse();
		assertThat(r.getCookie("JSESSIONID")).isNull();
	}

	// 조건 7: 허용 목록 밖 경로는 로그인이 필요해 401이다 (의도된 설계)
	@Test
	void 허용_목록_밖_경로는_로그인_없이_401이다() throws Exception {
		mockMvc.perform(get("/no-such-path")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}
}
