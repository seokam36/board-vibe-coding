package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.example.demo.chat.service.ChatSlots;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.chat.service.ChatGlobalLimiter;
import com.example.demo.user.domain.User;

import jakarta.servlet.http.Cookie;

// S18-d: 챗봇 서버 전체 질문 상한(모든 회원 합계: 분당 3질문 + 하루 5질문, 넘으면 503).
// 테스트 기본은 0(검사 안 함)이라 여기서만 3/5로 덮어쓴다. 회원별 한도는 분 2·하루 30이다.
// ChatGlobalLimitOrderTests와 설정을 똑같이 맞춘 것은 스프링 컨텍스트(DB·Redis 연결 포함)를 같이 쓰려는 것이다.
// 가짜 시계(SkewClock)로 "다음 분"·"태평양 자정"을 만든다. 질문마다 다른 회원을 써서 회원별 제한과 섞이지 않게 한다
// (한 회원이 이 클래스에서 묻는 횟수는 분당 최대 2번이라 회원별 429가 걸리지 않는다).
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = { "app.chat.rate-limit.per-minute=2", "app.chat.rate-limit.per-day=30",
		"app.chat.global-limit.per-minute=3", "app.chat.global-limit.per-day=5" })
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatGlobalLimitTests extends ChatTestBase {

	static final ZoneId 서울 = ZoneId.of("Asia/Seoul");
	static final ZoneId 태평양 = ZoneId.of("America/Los_Angeles");

	@Autowired ScriptedLlmClient.SkewClock clock;
	@Autowired ChatGlobalLimiter limiter;
	@Autowired ChatSlots slots;

	int 회원번호 = 0;

	@BeforeEach
	void 글과_AI_준비() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		// 기본은 태평양 정오(하루 경계에서 먼 시각). 테스트가 필요하면 시계를_맞추기로 다시 옮긴다.
		시계를_맞추기(LocalDate.now(clock).atTime(12, 0).atZone(태평양).toInstant());
	}

	private void 시계를_맞추기(Instant 목표) {
		clock.advance(Duration.between(clock.instant(), 목표));
	}

	// 매번 새 회원이 질문한다(처음 보는 회원 = 회원별 카운터 0)
	private MockHttpServletResponse 새_회원이_질문() throws Exception {
		User 회원 = TestLogin.사용자(userRepository, "g" + (++회원번호));
		return 질문하기(TestLogin.쿠키(tokenService, 회원));
	}

	private MockHttpServletResponse 질문하기(Cookie 누가) throws Exception {
		return mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", "자전거 있어?"))).cookie(누가).with(csrf())).andReturn()
				.getResponse();
	}

	@Test
	void 서로_다른_회원_3개_질문은_통과하고_4번째는_503이며_AI도_안_부른다() throws Exception {
		for (int i = 1; i <= 3; i++) {
			assertThat(새_회원이_질문().getStatus()).as(i + "번째").isEqualTo(200);
		}
		int json = llm.jsonCalls.size();
		int chat = llm.chatCalls.size();

		MockHttpServletResponse r = 새_회원이_질문();

		assertThat(r.getStatus()).isEqualTo(503);
		assertThat(r.getContentAsString()).doesNotContain("상한").doesNotContain("전체"); // 응답 본문에 원인 문구 없음
		assertThat(llm.jsonCalls).hasSize(json); // AI(LlmClient)를 안 불렀다
		assertThat(llm.chatCalls).hasSize(chat);
	}

	@Test
	void 시계를_다음_분으로_돌리면_다시_통과한다() throws Exception {
		for (int i = 0; i < 3; i++) {
			새_회원이_질문();
		}
		assertThat(새_회원이_질문().getStatus()).isEqualTo(503);

		clock.advance(Duration.ofMinutes(1));

		assertThat(새_회원이_질문().getStatus()).isEqualTo(200);
	}

	@Test
	void 분을_바꿔_하루_5개는_통과하고_6번째는_503이며_태평양_자정이_지나면_다시_통과한다() throws Exception {
		// 태평양 23:50부터 1분씩 건너뛰며 5개 질문(분 한도 3은 매번 새 분이라 안 걸린다)
		시계를_맞추기(LocalDate.now(clock.withZone(태평양)).atTime(23, 50).atZone(태평양).toInstant());
		for (int i = 1; i <= 5; i++) {
			assertThat(새_회원이_질문().getStatus()).as(i + "번째").isEqualTo(200);
			clock.advance(Duration.ofMinutes(1));
		}

		assertThat(새_회원이_질문().getStatus()).as("태평양 23:55 = 하루 한도").isEqualTo(503);

		// 태평양 자정 직후(00:01)로 이동 -> 새 태평양 날짜라 카운터가 새로 시작한다
		LocalDate 다음날 = LocalDate.now(clock.withZone(태평양)).plusDays(1);
		시계를_맞추기(다음날.atTime(0, 1).atZone(태평양).toInstant());
		assertThat(새_회원이_질문().getStatus()).isEqualTo(200);
	}

	// 서울 오전 = 태평양으로는 아직 전날 오후다. 하루 키는 서울 날짜가 아니라 태평양 날짜여야 한다.
	@Test
	void 서울_오전에는_하루_키가_태평양_날짜_즉_서울보다_하루_전이다() throws Exception {
		LocalDate 서울날짜 = LocalDate.now(clock.withZone(서울));
		시계를_맞추기(서울날짜.atTime(9, 0).atZone(서울).toInstant());
		LocalDate 서울오늘 = LocalDate.now(clock.withZone(서울));
		LocalDate 태평양오늘 = LocalDate.now(clock.withZone(태평양));
		assertThat(태평양오늘).as("서울 오전 9시 = 태평양 전날").isEqualTo(서울오늘.minusDays(1));

		새_회원이_질문();

		assertThat(redis.keys("chat-global-day:*")).containsExactly("chat-global-day:" + 태평양오늘);
	}

	@Test
	void 서울_자정이_지나도_태평양_날짜가_같으면_하루_한도는_이어진다() throws Exception {
		// 서울 다음 날 00:10 = 태평양으로는 아직 같은 날(전날 오전~오후). 하루 5개를 서울 자정 앞뒤로 나눠 쓴다.
		LocalDate 서울날짜 = LocalDate.now(clock.withZone(서울));
		시계를_맞추기(서울날짜.plusDays(1).atTime(0, 0).atZone(서울).minusMinutes(2).toInstant());
		for (int i = 1; i <= 5; i++) {
			assertThat(새_회원이_질문().getStatus()).as(i + "번째").isEqualTo(200);
			clock.advance(Duration.ofMinutes(1)); // 서울 자정을 넘나든다
		}

		assertThat(새_회원이_질문().getStatus()).isEqualTo(503);
	}

	@Test
	void 막힌_요청은_전체_카운터를_늘리지_않고_회원_카운터는_이미_1_늘어_있다() throws Exception {
		for (int i = 0; i < 3; i++) {
			새_회원이_질문();
		}
		User 막힐회원 = TestLogin.사용자(userRepository, "blocked");
		Cookie 쿠키2 = TestLogin.쿠키(tokenService, 막힐회원);
		for (int i = 0; i < 2; i++) {
			assertThat(질문하기(쿠키2).getStatus()).isEqualTo(503);
		}

		long 분 = clock.instant().getEpochSecond() / 60;
		LocalDate 태평양날짜 = LocalDate.now(clock.withZone(태평양));
		assertThat(redis.opsForValue().get("chat-global-min:" + 분)).as("전체 분 카운터는 통과한 3개뿐").isEqualTo("3");
		assertThat(redis.opsForValue().get("chat-global-day:" + 태평양날짜)).isEqualTo("3");
		// 기존 "503도 셈" 규칙: 막혀도 회원별 카운터는 늘어난다(회원별 검사가 전체 검사보다 먼저라서)
		assertThat(redis.opsForValue().get("chat-rate-min:" + 막힐회원.getId() + ":" + 분)).isEqualTo("2");
	}

	// 막힌 요청은 동시 질문 자리를 잡지도 않는다(자리 잡기 전에 막히므로). 자리 수는 처음 그대로여야 한다.
	@Test
	void 전체_상한으로_막힌_요청은_자리를_잡지_않고_자리가_모두_돌아와_있다() throws Exception {
		int 처음자리 = slots.availablePermits();
		for (int i = 0; i < 3; i++) {
			새_회원이_질문();
		}
		assertThat(slots.availablePermits()).isEqualTo(처음자리);

		assertThat(새_회원이_질문().getStatus()).isEqualTo(503);

		assertThat(slots.availablePermits()).isEqualTo(처음자리);
	}

	// 로그로만 원인을 구분한다: 분·하루 문구가 다르고, 회원 id·질문 내용은 없다
	@Test
	void 막히면_원인_로그가_남고_회원_id와_질문은_없다(CapturedOutput output) throws Exception {
		for (int i = 0; i < 3; i++) {
			새_회원이_질문();
		}
		User 막힐회원 = TestLogin.사용자(userRepository, "logtarget");

		질문하기(TestLogin.쿠키(tokenService, 막힐회원));

		// "전체 상한"이 든 로그 줄만 골라, 로거 접두(시각·스레드 등)를 뺀 메시지가 정확히 이 한 문장인지 본다
		// (회원 id·이름·질문이 끼어들면 문장이 달라져서 깨진다)
		List<String> 상한줄 = output.getAll().lines().filter(줄 -> 줄.contains("전체 상한")).toList();
		assertThat(상한줄).hasSize(1);
		assertThat(상한줄.get(0)).endsWith(": 챗봇 서버 전체 상한(분) 넘음 -> 503");
		assertThat(output.getAll()).doesNotContain("자전거 있어?").doesNotContain("logtarget");
	}

	@Test
	void 하루_상한으로_막히면_하루_문구가_로그에_남는다(CapturedOutput output) throws Exception {
		for (int i = 0; i < 5; i++) {
			새_회원이_질문();
			clock.advance(Duration.ofMinutes(1)); // 분 한도는 안 걸리게 분을 옮긴다
		}

		assertThat(새_회원이_질문().getStatus()).isEqualTo(503);

		assertThat(output.getAll()).contains("챗봇 서버 전체 상한(하루) 넘음 -> 503");
	}

	@Test
	void 카운터_키에_만료_시간이_걸린다() throws Exception {
		새_회원이_질문();

		Long 분TTL = redis.getExpire(redis.keys("chat-global-min:*").iterator().next());
		Long 하루TTL = redis.getExpire(redis.keys("chat-global-day:*").iterator().next());
		assertThat(분TTL).isBetween(1L, 120L);
		assertThat(하루TTL).isBetween(25L * 3600, 26L * 3600); // 서머타임 끝나는 날(25시간)도 덮도록 넉넉히
	}

	@Test
	void 여러_회원이_동시에_10개를_보내도_정확히_3개만_통과한다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(10);
		CountDownLatch ready = new CountDownLatch(10);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		List<Future<?>> fs = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			fs.add(pool.submit(() -> {
				ready.countDown();
				go.await(); // 전원 준비되면 한꺼번에 출발(회원이 달라도 전체 카운터는 하나)
				try {
					limiter.countOrReject();
					ok.incrementAndGet();
				} catch (ResponseStatusException e) {
					assertThat(e.getStatusCode().value()).isEqualTo(503);
					rejected.incrementAndGet();
				}
				return null;
			}));
		}
		assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
		go.countDown();
		for (Future<?> f : fs) {
			f.get(10, TimeUnit.SECONDS);
		}
		pool.shutdown();

		assertThat(ok.get()).isEqualTo(3);
		assertThat(rejected.get()).isEqualTo(7);
		Set<String> 분키 = redis.keys("chat-global-min:*");
		assertThat(redis.opsForValue().get(분키.iterator().next())).isEqualTo("3"); // 10이 아니라 3
	}
}
