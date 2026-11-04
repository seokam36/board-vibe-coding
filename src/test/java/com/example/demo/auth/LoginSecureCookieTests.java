package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

// app.cookie.secure=true(운영의 COOKIE_SECURE=true)이면 로그인 쿠키에 Secure 속성이 붙는지 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.cookie.secure=true")
class LoginSecureCookieTests {

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

	@Test
	void secure_설정이_true면_로그인_쿠키에_Secure_속성이_있다() throws Exception {
		String body = "{\"username\":\"user01\",\"password\":\"password123\"}";
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn();
		MockHttpServletResponse res = mockMvc
				.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(res.getHeader(HttpHeaders.SET_COOKIE)).startsWith("access_token=").contains("Secure");
	}
}
