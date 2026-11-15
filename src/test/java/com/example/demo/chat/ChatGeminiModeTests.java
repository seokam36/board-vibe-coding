package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.llm.service.GeminiLlmClient;
import com.example.demo.llm.service.LlmClient;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S18-c: LLM_MODE=gemini로 앱 전체를 띄우고, 진짜 구글 대신 가짜 서버(JDK HttpServer)가 답하게 해서 POST /chat 전체 길을 본다.
// 진짜 제미나이는 부르지 않는다. 가짜 서버 주소는 app.llm.gemini.base-url 속성으로 바꾼다(전용 환경변수 GEMINI_BASE_URL은 일부러 없다. 스프링 규칙상 환경변수로도 바뀌지만 LlmConfig가 https·내 컴퓨터 http만 허용한다).
@SpringBootTest(properties = { "app.llm.mode=gemini", "app.llm.gemini.api-key=" + ChatGeminiModeTests.KEY })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ChatGeminiModeTests {

	static final String KEY = "TESTKEY-never-real-12345";
	private static final String ERROR_TEXT = "ERRMSG-LEAK-CHECK";

	// 가짜 구글 서버. 스프링이 뜨기 전에 포트가 정해져야 해서 static으로 먼저 띄운다.
	private static final HttpServer SERVER = startServer();
	// 가짜 서버가 받은 요청 본문들(양식 호출·답 호출 순서)과 키 헤더
	private static final List<String> BODIES = new CopyOnWriteArrayList<>();
	private static final List<String> KEY_HEADERS = new CopyOnWriteArrayList<>();
	// 0이면 정상으로 답하고, 아니면 그 상태 코드로 오류를 돌려준다
	private static volatile int errorStatus = 0;

	private static HttpServer startServer() {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			s.createContext("/", ex -> {
				try {
					String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
					BODIES.add(body);
					KEY_HEADERS.add(String.valueOf(ex.getRequestHeaders().getFirst("x-goog-api-key")));
					int status = errorStatus;
					String reply;
					if (status != 0) {
						reply = "{\"error\":{\"code\":" + status + ",\"message\":\"" + ERROR_TEXT + "\",\"status\":\"RESOURCE_EXHAUSTED\"}}";
					} else if (body.contains("responseJsonSchema")) {
						reply = ok("{\"keyword\":\"자전거\"}"); // 양식 호출: 키워드만 채운다
					} else {
						reply = ok("자전거가 있어요");
					}
					byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
					ex.getResponseHeaders().add("Content-Type", "application/json");
					ex.sendResponseHeaders(status == 0 ? 200 : status, bytes.length);
					ex.getResponseBody().write(bytes);
				} finally {
					ex.close();
				}
			});
			s.start();
			return s;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static String ok(String text) {
		ObjectMapper m = new ObjectMapper();
		return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + m.writeValueAsString(text)
				+ "}]},\"finishReason\":\"STOP\"}]}";
	}

	@DynamicPropertySource
	static void fakeGoogle(DynamicPropertyRegistry registry) {
		registry.add("app.llm.gemini.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
	}

	@AfterAll
	static void stopServer() {
		SERVER.stop(0);
	}

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired LlmClient llmClient;

	Cookie 판매자쿠키;
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		BODIES.clear();
		KEY_HEADERS.clear();
		errorStatus = 0;
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "gmseller", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		쿠키 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "gmasker"));
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private void 글_올리기() throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("title", "자전거 팝니다", "content", "내용", "tradeType", "SALE", "price", 1000)))
				.cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(201);
	}

	private MockHttpServletResponse 질문() throws Exception {
		return mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", "자전거 있어?"))).cookie(쿠키).with(csrf())).andReturn().getResponse();
	}

	@Test
	void gemini_모드면_Gemini_구현이_등록되고_켜져_있다() {
		assertThat(llmClient).isInstanceOf(GeminiLlmClient.class);
		assertThat(llmClient.isEnabled()).isTrue();
	}

	@Test
	void 질문하면_200과_answer_sources를_돌려주고_양식_호출에만_responseJsonSchema가_실린다() throws Exception {
		글_올리기();

		MockHttpServletResponse r = 질문();

		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode json = om.readTree(r.getContentAsString());
		assertThat(json.get("answer").asString()).isEqualTo("자전거가 있어요");
		assertThat(json.get("sources")).hasSize(1);
		assertThat(json.get("sources").get(0).get("title").asString()).isEqualTo("자전거 팝니다");
		// 호출은 정확히 2번: 1번째 = 양식 호출(스키마 있음), 2번째 = 답 호출(스키마 없음)
		assertThat(BODIES).hasSize(2);
		assertThat(BODIES.get(0)).contains("responseJsonSchema").contains("application/json");
		assertThat(BODIES.get(1)).doesNotContain("responseJsonSchema");
		// 키는 헤더로만 간다
		assertThat(KEY_HEADERS).containsOnly(KEY);
		assertThat(BODIES.get(0) + BODIES.get(1)).doesNotContain(KEY);
	}

	@Test
	void 구글이_429를_주면_503이고_응답에_원인_문구가_없다() throws Exception {
		글_올리기();
		errorStatus = 429;

		MockHttpServletResponse r = 질문();

		assertThat(r.getStatus()).isEqualTo(503);
		assertThat(BODIES).hasSize(1); // 가짜 서버에 닿았고, 실패한 양식 호출을 다시 시도하지 않았다(재시도 0)
		assertThat(r.getContentAsString()).doesNotContain(ERROR_TEXT).doesNotContain("RESOURCE_EXHAUSTED").doesNotContain(KEY);
	}

	@Test
	void 구글이_401을_주면_503이다() throws Exception {
		글_올리기();
		errorStatus = 401;

		MockHttpServletResponse r = 질문();

		assertThat(r.getStatus()).isEqualTo(503);
		assertThat(BODIES).hasSize(1); // 서버 도달 + 재시도 0
		assertThat(r.getContentAsString()).doesNotContain(ERROR_TEXT).doesNotContain(KEY);
	}

	// ---- 키가 밖으로 새지 않는지 ----

	@Test
	void AI_클라이언트_toString에_키가_없다() {
		// 설정 객체(LlmProperties)의 toString은 LlmConfigTests가 본다
		assertThat(llmClient.toString()).doesNotContain(KEY);
	}

	// 노출한 actuator는 health뿐이라 env·configprops는 로그인한 사람에게도 없는 경로(404)고, 로그인 안 하면 401이다. 어느 쪽이든 본문에 키는 없다.
	@Test
	void actuator_env와_configprops는_열려_있지_않고_키가_나오지_않는다() throws Exception {
		for (String path : new String[] { "/actuator/env", "/actuator/configprops", "/actuator/env/app.llm.gemini.api-key" }) {
			MockHttpServletResponse loggedIn = mockMvc.perform(get(path).cookie(쿠키)).andReturn().getResponse();
			assertThat(loggedIn.getStatus()).as(path + " (로그인)").isEqualTo(404);
			assertThat(loggedIn.getContentAsString()).doesNotContain(KEY);

			MockHttpServletResponse anonymous = mockMvc.perform(get(path)).andReturn().getResponse();
			assertThat(anonymous.getStatus()).as(path + " (비로그인)").isEqualTo(401);
			assertThat(anonymous.getContentAsString()).doesNotContain(KEY);
		}
	}
}
