package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.repository.UserRepository;

// 로그인 중 DB 오류 같은 내부 문제는 "비밀번호 틀림(401)"으로 숨기지 말고 서버 오류(5xx)여야 한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class LoginInternalErrorTests {

	@Autowired
	MockMvc mockMvc;

	// 사용자 조회(UserRepository.findByUsername) 단계가 RuntimeException을 던지게 만든다 (Security가 InternalAuthenticationServiceException으로 감싼다)
	@MockitoSpyBean
	UserRepository userRepository;

	@Test
	void 인증_중_내부_오류가_나면_401이_아니라_5xx이다() throws Exception {
		doThrow(new RuntimeException("DB 장애 흉내")).when(userRepository).findByUsername(anyString());

		int status;
		try {
			status = mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
					.content("{\"username\":\"user01\",\"nickname\":\"n_user01\",\"password\":\"password123\"}")).andReturn().getResponse()
					.getStatus();
		} catch (Exception e) {
			// MockMvc는 처리 안 된 예외를 응답 대신 던진다. 실서버에서는 이것이 500이 되므로 "401로 숨겨지지 않음"으로 판정한다.
			assertThat(e.getCause()).isNotNull();
			status = 500;
		}
		// 스파이가 실제로 호출됐는지(가짜 오류가 심어졌는지) 먼저 확인한다
		org.mockito.Mockito.verify(userRepository).findByUsername(anyString());
		assertThat(status).isGreaterThanOrEqualTo(500);
	}
}
