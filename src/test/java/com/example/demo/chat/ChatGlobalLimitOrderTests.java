package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import com.example.demo.TestcontainersConfiguration;

// S18-d 검사 순서 확인: 서버 전체 카운터는 "회원별 검사까지 통과한 질문"만 센다.
// 회원별 한도(분당 2)에 먼저 걸리도록 하고(전체는 분 3·하루 5), 회원별 429·400·401·403이 전체 카운터를 늘리는지 본다.
// 설정을 ChatGlobalLimitTests와 똑같이 맞춘 것은 스프링 컨텍스트를 같이 쓰려는 것이다.
// (이 클래스에서 통과하는 질문은 한 테스트에 2개뿐이라 전체 상한 3·5에 안 걸린다)
@SpringBootTest(properties = { "app.chat.rate-limit.per-minute=2", "app.chat.rate-limit.per-day=30",
		"app.chat.global-limit.per-minute=3", "app.chat.global-limit.per-day=5" })
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatGlobalLimitOrderTests extends ChatTestBase {

	@Autowired ScriptedLlmClient.SkewClock clock;

	@BeforeEach
	void AI_준비() {
		llm.jsonReturns("{\"keyword\":\"\"}"); // 인사 취급 -> 200 고정 문구(글 준비 불필요)
	}

	private String 전체_분_카운터() {
		long 분 = clock.instant().getEpochSecond() / 60;
		return redis.opsForValue().get("chat-global-min:" + 분);
	}

	// 회원별 429로 막힌 요청은 서버 전체 카운터를 늘리지 않는다(회원별 검사가 전체 검사보다 앞이라서)
	@Test
	void 회원별_429로_막힌_요청은_전체_카운터를_늘리지_않는다() throws Exception {
		assertThat(질문("안녕").getStatus()).isEqualTo(200);
		assertThat(질문("안녕").getStatus()).isEqualTo(200);
		for (int i = 0; i < 3; i++) {
			assertThat(질문("안녕").getStatus()).isEqualTo(429);
		}

		assertThat(전체_분_카운터()).as("통과한 2개만").isEqualTo("2");
		LocalDate 태평양날짜 = LocalDate.now(clock.withZone(ZoneId.of("America/Los_Angeles")));
		assertThat(redis.opsForValue().get("chat-global-day:" + 태평양날짜)).isEqualTo("2");
	}

	// 질문 형식 검사(@Valid)는 컨트롤러에서 끝나므로 서비스(전체 카운터)까지 오지 않는다
	@Test
	void 형식이_틀린_400_요청은_전체_카운터도_회원_카운터도_늘리지_않는다() throws Exception {
		String 너무_긴_질문 = "가".repeat(501);
		for (String q : new String[] { "", "   ", 너무_긴_질문 }) {
			int status = mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
					.content(om.writeValueAsString(Map.of("question", q))).cookie(쿠키).with(csrf())).andReturn()
					.getResponse().getStatus();
			assertThat(status).as("질문 길이 " + q.length()).isEqualTo(400);
		}

		assertThat(redis.keys("chat-global-*")).isEmpty();
		assertThat(redis.keys("chat-rate-*")).isEmpty();
	}

	// 401·403 자체는 ChatRateLimitExtraTests에도 있다. 여기서는 "서버 전체 상한이 켜진 컨텍스트에서" 전체 카운터 키가 안 생기는지를 본다.
	@Test
	void 로그인_없는_401과_CSRF_없는_403은_전체_카운터를_늘리지_않는다() throws Exception {
		int s401 = mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", "안녕"))).with(csrf())).andReturn().getResponse()
				.getStatus();
		int s403 = mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", "안녕"))).cookie(쿠키)).andReturn().getResponse()
				.getStatus();

		assertThat(s401).isEqualTo(401);
		assertThat(s403).isEqualTo(403);
		assertThat(redis.keys("chat-global-*")).isEmpty();
	}
}
