package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S17-e5 테스트 공통 도우미: 판매자·질문자 준비, 글 올리기, POST /chat 부르기, sources id 꺼내기.
// @SpringBootTest 같은 설정 어노테이션은 각 테스트 클래스에 붙인다(여기에는 도우미만).
abstract class ChatTestBase {

	static final String 판매자이름 = "e5seller";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired ScriptedLlmClient llm;
	@Autowired StringRedisTemplate redis;

	Cookie 판매자쿠키;
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		llm.reset();
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, 판매자이름, "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		쿠키 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "e5asker"));
		llm.chatReturns("답");
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
		횟수키_지우기();
	}

	// S17-f1: 챗봇 횟수 제한 카운터(Redis chat-rate-* 키)를 지운다. 테스트끼리 횟수가 새지 않게 한다.
	void 횟수키_지우기() {
		Set<String> keys = redis.keys("chat-rate-*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
		// S18-d: 서버 전체 상한 카운터(chat-global-*)도 같이 지운다
		Set<String> globalKeys = redis.keys("chat-global-*");
		if (globalKeys != null && !globalKeys.isEmpty()) {
			redis.delete(globalKeys);
		}
	}

	long 올리기(String title, String tradeType, Integer price) throws Exception {
		return 올리기(title, "내용", tradeType, price);
	}

	long 올리기(String title, String content, String tradeType, Integer price) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		body.put("content", content);
		body.put("tradeType", tradeType);
		if (price != null) {
			body.put("price", price);
		}
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(body)).cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기: " + title).isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	void 상태바꾸기(long id, String status) {
		jdbc.update("update market_items set status = ? where id = ?", status, id);
	}

	MockHttpServletResponse 질문(String question) throws Exception {
		return mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", question))).cookie(쿠키).with(csrf())).andReturn()
				.getResponse();
	}

	List<Long> 출처ids(MockHttpServletResponse r) throws Exception {
		List<Long> ids = new ArrayList<>();
		for (JsonNode s : om.readTree(r.getContentAsString()).get("sources")) {
			ids.add(s.get("id").asLong());
		}
		return ids;
	}
}
