package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.repository.UserRepository;

// S4-a 스모크 테스트: 회원가입 핵심 동작만 빠르게 확인한다 (자세한 케이스는 tester가 추가)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SignupSmokeTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	UserRepository userRepository;

	@Autowired
	PasswordEncoder passwordEncoder;

	@AfterEach
	void 정리() {
		userRepository.deleteAll();
	}

	private ResultActions 가입(String username, String password) throws Exception {
		return mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
	}

	@Test
	void 가입하면_201과_id_username만_반환하고_비밀번호는_해시로_저장된다() throws Exception {
		가입("user01", "password123")
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.username").value("user01"))
				.andExpect(jsonPath("$.password").doesNotExist());
		String saved = userRepository.findAll().get(0).getPassword();
		assertThat(saved).isNotEqualTo("password123");
		assertThat(passwordEncoder.matches("password123", saved)).isTrue();
	}

	@Test
	void 같은_아이디로_다시_가입하면_409() throws Exception {
		가입("user01", "password123").andExpect(status().isCreated());
		가입("user01", "password456").andExpect(status().isConflict());
		assertThat(userRepository.count()).isEqualTo(1);
	}

	@Test
	void 한글_25자_비밀번호는_75바이트라_400() throws Exception {
		가입("user01", "가".repeat(25)).andExpect(status().isBadRequest());
	}

	@Test
	void CSRF_토큰_없이_가입하면_403() throws Exception {
		mockMvc.perform(post("/auth/signup").contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"user01\",\"password\":\"password123\"}"))
				.andExpect(status().isForbidden());
	}

	@Test
	void csrf_엔드포인트는_200과_헤더이름을_반환한다() throws Exception {
		mockMvc.perform(get("/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.headerName").isNotEmpty());
	}
}
