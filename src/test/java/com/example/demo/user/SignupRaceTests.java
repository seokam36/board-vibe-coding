package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

// 동시 가입 상황 재현: existsByUsername 확인을 통과해도 DB unique 제약이 막아 409가 되는지 검사한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SignupRaceTests {

	@Autowired
	MockMvc mockMvc;

	// 실제 리포지토리를 감싼 스파이: existsByUsername만 거짓말(false)을 하게 만든다
	@MockitoSpyBean
	UserRepository userRepository;
	@Autowired
	PostRepository postRepository;

	@AfterEach
	void 정리() {
		// 글이 사용자를 참조(외래키)하므로 글부터 지운다
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private org.springframework.test.web.servlet.ResultActions 가입(String password) throws Exception {
		return mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"user01\",\"password\":\"" + password + "\"}"));
	}

	@Test
	void 사전_확인을_통과해도_DB_unique_위반이면_409이고_행이_늘지_않는다() throws Exception {
		가입("password123").andExpect(status().isCreated());
		doReturn(false).when(userRepository).existsByUsername(anyString());

		가입("password456").andExpect(status().isConflict());

		assertThat(userRepository.count()).isEqualTo(1);
	}
}
