package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

// S17-i: 횟수를 넘긴 429의 "진짜 응답 본문"에 원인 문구가 없는지 진짜 서버(실제 포트)로 본다. MockMvc는 /error 본문을 만들지 않아 거기서는 이 확인이 헛돈다.
// 테스트 기본 한도는 아주 크게 잡혀 있어서(test application.properties) 여기서만 분당 5번으로 낮춘다(그래서 ChatRealServerTests와 따로 둔다).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "app.chat.rate-limit.per-minute=5", "app.chat.rate-limit.per-day=30" })
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
class ChatRealServerRateLimitTests {

	@LocalServerPort int port;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired ScriptedLlmClient llm;

	final HttpClient client = HttpClient.newHttpClient();
	String 로그인쿠키;

	@BeforeEach
	void 준비() {
		정리();
		llm.reset();
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		User 질문자 = TestLogin.사용자(userRepository, "realratelimit");
		로그인쿠키 = "access_token=" + TestLogin.쿠키(tokenService, 질문자).getValue();
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private HttpResponse<String> 질문(String question) throws Exception {
		String base = "http://localhost:" + port;
		HttpResponse<String> c = client.send(HttpRequest.newBuilder(URI.create(base + "/csrf")).build(), BodyHandlers.ofString());
		String xsrf = c.headers().allValues("Set-Cookie").stream().filter(s -> s.startsWith("XSRF-TOKEN="))
				.map(s -> s.split(";")[0].substring("XSRF-TOKEN=".length())).findFirst().orElseThrow();
		return client.send(HttpRequest.newBuilder(URI.create(base + "/chat"))
				.header("Content-Type", "application/json")
				.header("Cookie", 로그인쿠키 + "; XSRF-TOKEN=" + xsrf).header("X-XSRF-TOKEN", xsrf)
				.POST(BodyPublishers.ofString(om.writeValueAsString(java.util.Map.of("question", question)))).build(),
				BodyHandlers.ofString());
	}

	@Test
	void 횟수를_넘긴_429는_실제_서버_본문에_원인_문구가_없다() throws Exception {
		llm.chatReturns("답");
		// 글이 없어 고정 문구(200)로 끝나는 질문도 1회로 센다
		for (int i = 0; i < 5; i++) {
			assertThat(질문("자전거").statusCode()).as((i + 1) + "번째").isNotEqualTo(429);
		}

		HttpResponse<String> r = 질문("자전거");

		assertThat(r.statusCode()).isEqualTo(429);
		assertThat(r.body()).doesNotContain("횟수");
		// 서버 설정(spring.web.error.include-message)이 켜져 reason이 새도 알 수 있게 message 칸도 본다
		if (!r.body().isBlank()) {
			var message = om.readTree(r.body()).path("message");
			assertThat(message.isMissingNode() || message.isNull() || message.asString().isEmpty())
					.as("본문 message 칸이 없거나 비어야 한다: %s", r.body()).isTrue();
		}
	}
}
