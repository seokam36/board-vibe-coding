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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

// S17-c: 가짜 AI(ScriptedLlmClient) 없이 진짜 OffLlmClient(LLM_MODE=off, 테스트 기본)로 본 POST /chat.
// 가짜 AI를 안 끼워서 기본 테스트 컨텍스트를 다른 테스트와 같이 쓴다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ChatOffModeTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired StringRedisTemplate redis;

	Cookie 판매자쿠키;
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "offseller", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		쿠키 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "offasker"));
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
		Set<String> keys = redis.keys("chat-rate-*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	private MockHttpServletResponse 질문(String question) throws Exception {
		return mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", question))).cookie(쿠키).with(csrf())).andReturn()
				.getResponse();
	}

	private void 글_올리기() throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("title", "자전거 팝니다", "content", "내용", "tradeType", "SALE",
						"price", 1000)))
				.cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(201);
	}

	@Test
	void LLM_MODE_off이고_맞는_글이_있으면_503이다() throws Exception {
		글_올리기();

		assertThat(질문("자전거").getStatus()).isEqualTo(503);
	}

	@Test
	void LLM_MODE_off이면_맞는_글이_0건이어도_503이다() throws Exception {
		// S17-f1: AI가 꺼져 있으면 AI를 부르기 전에 바로 503이다(글이 0건이어도 같고, 횟수도 안 센다)
		assertThat(질문("자전거").getStatus()).isEqualTo(503);
	}

	// S17-f1: 꺼진 모드는 횟수를 세지 않고 바로 503이다 -> Redis에 chat-rate-* 키가 하나도 안 생긴다.
	// (한도를 낮추지 않아도 키 유무로 "안 셌다"를 확인할 수 있다)
	@Test
	void off_모드에서_여러_번_물어도_전부_503이고_횟수_키가_안_생긴다() throws Exception {
		for (int i = 0; i < 10; i++) {
			assertThat(질문("자전거 있어?").getStatus()).as((i + 1) + "번째").isEqualTo(503);
		}

		assertThat(redis.keys("chat-rate-*")).isEmpty();
	}
}
