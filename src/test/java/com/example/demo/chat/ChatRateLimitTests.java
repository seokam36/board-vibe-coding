package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.llm.service.LlmTimeoutException;
import com.example.demo.llm.service.LlmUnavailableException;
import com.example.demo.user.domain.User;

import jakarta.servlet.http.Cookie;

// S17-f1: 챗봇 회원별 횟수 제한(분당 5회 + 하루 30회, 넘으면 429).
// 테스트 기본 한도는 아주 크게 잡혀 있어서(test application.properties) 여기서만 5/30으로 덮어쓴다.
// 가짜 AI(ScriptedLlmClient)와 가짜 시계(SkewClock)를 써서 진짜 AI·진짜 기다림 없이 "1분 뒤"·"다음 날"을 만든다.
@SpringBootTest(properties = { "app.chat.rate-limit.per-minute=5", "app.chat.rate-limit.per-day=30" })
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatRateLimitTests extends ChatTestBase {

	static final ZoneId 서울 = ZoneId.of("Asia/Seoul");

	@Autowired ScriptedLlmClient.SkewClock clock;

	@BeforeEach
	void 시계를_오늘_정오로() throws Exception {
		// 분 번호·날짜가 가짜 시계로 정해지므로, 테스트 중간에 자정이 끼어 하루 카운터가 흔들리지 않게 서울 정오로 고정한다.
		var 정오 = LocalDate.now(clock).atTime(12, 0).atZone(서울).toInstant();
		clock.advance(Duration.between(clock.instant(), 정오));
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
	}

	private MockHttpServletResponse 질문(Cookie 누가, String question) throws Exception {
		return mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", question))).cookie(누가).with(csrf())).andReturn()
				.getResponse();
	}

	private void 다섯_번_통과(Cookie 누가) throws Exception {
		for (int i = 0; i < 5; i++) {
			assertThat(질문(누가, "자전거 있어?").getStatus()).as((i + 1) + "번째").isNotEqualTo(429);
		}
	}

	@Test
	void 같은_분에_5번은_통과하고_6번째는_429이며_AI를_부르지_않는다() throws Exception {
		다섯_번_통과(쿠키);
		int json = llm.jsonCalls.size();
		int chat = llm.chatCalls.size();

		MockHttpServletResponse r = 질문("자전거 있어?");

		assertThat(r.getStatus()).isEqualTo(429);
		assertThat(r.getContentAsString()).doesNotContain("횟수"); // 본문에 문구 없음(기존 방식)
		assertThat(llm.jsonCalls).hasSize(json);
		assertThat(llm.chatCalls).hasSize(chat);
	}

	@Test
	void 시계를_1분_앞으로_돌리면_다시_통과한다() throws Exception {
		다섯_번_통과(쿠키);
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);

		clock.advance(Duration.ofMinutes(1));

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(200);
	}

	@Test
	void 분을_바꿔_하루_30번은_통과하고_31번째는_429이며_다음_날이면_다시_통과한다() throws Exception {
		for (int i = 0; i < 30; i++) {
			assertThat(질문("자전거 있어?").getStatus()).as((i + 1) + "번째").isNotEqualTo(429);
			clock.advance(Duration.ofMinutes(1)); // 분 한도(5)는 매번 새 분이라 안 걸린다
		}

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);

		clock.advance(Duration.ofDays(1)); // 서울 기준 다음 날

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(200);
	}

	@Test
	void 하루_한도에_막힌_요청은_그_분의_분_카운터도_늘리지_않는다() throws Exception {
		for (int i = 0; i < 30; i++) {
			질문("자전거 있어?");
			clock.advance(Duration.ofMinutes(1));
		}

		// 하루 30을 다 쓴 상태에서 새 분에 한 번 더 -> 하루 한도 때문에 429
		long 새분 = clock.instant().getEpochSecond() / 60;
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);

		// 막힌 요청은 이 새 분의 카운터 키를 만들지도 늘리지도 않는다(키가 없거나 0)
		Set<String> 새분키 = redis.keys("chat-rate-min:*:" + 새분);
		assertThat(새분키).isEmpty();
	}

	@Test
	void 서울_자정을_넘기면_하루_카운터가_새로_시작한다() throws Exception {
		// 30번을 모두 쓰고 31번째가 막힌 뒤, 자정 직전 -> 직후로 시계를 옮기면 풀린다(UTC 자정이 아니라 서울 자정 기준)
		for (int i = 0; i < 30; i++) {
			질문("자전거 있어?");
			clock.advance(Duration.ofMinutes(1));
		}
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);

		var 자정 = LocalDate.now(clock).plusDays(1).atStartOfDay(서울).toInstant();
		clock.advance(Duration.between(clock.instant(), 자정.minusSeconds(60)));
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429); // 아직 같은 서울 날짜
		clock.advance(Duration.ofSeconds(120));
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(200); // 서울 날짜가 바뀜
	}

	@Test
	void 회원_A가_막혀도_회원_B는_통과하고_관리자도_6번째는_막힌다() throws Exception {
		다섯_번_통과(쿠키);
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);

		Cookie 다른회원 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "f1other"));
		assertThat(질문(다른회원, "자전거 있어?").getStatus()).isEqualTo(200);

		User 관리자 = TestLogin.관리자(userRepository, jdbc, "f1admin");
		Cookie 관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		다섯_번_통과(관리자쿠키);
		assertThat(질문(관리자쿠키, "자전거 있어?").getStatus()).isEqualTo(429);
	}

	@Test
	void 형식이_틀려_400이_된_요청은_세지_않는다() throws Exception {
		for (int i = 0; i < 5; i++) {
			assertThat(질문("").getStatus()).isEqualTo(400);
			assertThat(질문("가".repeat(501)).getStatus()).isEqualTo(400);
		}

		다섯_번_통과(쿠키);
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);
	}

	@Test
	void 고정_문구_200으로_끝난_요청도_1회로_센다() throws Exception {
		llm.jsonReturns("{\"keyword\":\"\"}"); // 인사 -> AI 답 없이 고정 문구
		for (int i = 0; i < 5; i++) {
			assertThat(질문("안녕").getStatus()).isEqualTo(200);
		}

		assertThat(질문("안녕").getStatus()).isEqualTo(429);
	}

	@Test
	void AI_503으로_끝난_요청도_1회로_센다() throws Exception {
		llm.jsonThrows(new LlmUnavailableException("테스트용"));
		for (int i = 0; i < 5; i++) {
			assertThat(질문("자전거 있어?").getStatus()).isEqualTo(503);
		}

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);
	}

	@Test
	void AI_504로_끝난_요청도_1회로_센다() throws Exception {
		llm.jsonThrows(new LlmTimeoutException("테스트용", null));
		for (int i = 0; i < 5; i++) {
			assertThat(질문("자전거 있어?").getStatus()).isEqualTo(504);
		}

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);
	}

	@Test
	void 막힌_429_요청은_카운터를_늘리지_않는다() throws Exception {
		다섯_번_통과(쿠키);
		for (int i = 0; i < 3; i++) {
			assertThat(질문("자전거 있어?").getStatus()).isEqualTo(429);
		}

		Set<String> 하루키 = redis.keys("chat-rate-day:*");
		Set<String> 분키 = redis.keys("chat-rate-min:*");
		assertThat(하루키).hasSize(1);
		assertThat(분키).hasSize(1);
		assertThat(redis.opsForValue().get(하루키.iterator().next())).isEqualTo("5"); // 8이 아니라 5
		assertThat(redis.opsForValue().get(분키.iterator().next())).isEqualTo("5");
	}

	@Test
	void 첫_질문에_카운터_키에_만료_시간이_걸린다() throws Exception {
		질문("자전거 있어?");

		Long 분TTL = redis.getExpire(redis.keys("chat-rate-min:*").iterator().next());
		Long 하루TTL = redis.getExpire(redis.keys("chat-rate-day:*").iterator().next());
		assertThat(분TTL).isBetween(1L, 120L);
		assertThat(하루TTL).isBetween(24L * 3600, 25L * 3600);
	}
}
