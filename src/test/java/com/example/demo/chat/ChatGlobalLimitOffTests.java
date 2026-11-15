package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;
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
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

// S18-d: 서버 전체 상한이 켜져 있어도(분 3·하루 5) AI가 꺼진 off 모드에서는 전체 카운터 키가 Redis에 생기지 않는다.
// (off는 ChatService.ask() 맨 앞에서 바로 503이라 전체 검사까지 가지 않는다.) 진짜 OffLlmClient를 쓰려고 가짜 AI는 끼우지 않는다.
// ChatOffModeTests와 겹치지 않는 점: 그쪽은 전체 상한이 0(꺼짐)이라 "키가 없다"가 당연하지만, 여기는 상한을 켠 채로 키가 안 생김을 본다.
@SpringBootTest(properties = { "app.chat.global-limit.per-minute=3", "app.chat.global-limit.per-day=5" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ChatGlobalLimitOffTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired StringRedisTemplate redis;

	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		쿠키 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "gofasker"));
	}

	@AfterEach
	void 정리() {
		userRepository.deleteAll();
		Set<String> keys = redis.keys("chat-*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	@Test
	void off_모드에서_여러_번_물어도_전부_503이고_전체_카운터_키가_안_생긴다() throws Exception {
		for (int i = 0; i < 10; i++) {
			int status = mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
					.content(om.writeValueAsString(Map.of("question", "자전거 있어?"))).cookie(쿠키).with(csrf())).andReturn()
					.getResponse().getStatus();
			assertThat(status).as((i + 1) + "번째").isEqualTo(503);
		}

		assertThat(redis.keys("chat-global-*")).isEmpty();
	}
}
