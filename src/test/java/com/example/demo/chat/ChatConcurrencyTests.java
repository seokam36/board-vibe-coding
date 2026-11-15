package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.chat.service.ChatSlots;
import com.example.demo.llm.service.LlmTimeoutException;
import com.example.demo.llm.service.LlmUnavailableException;

// S17-f2: 챗봇 동시 질문 상한(서버 전체 2개). 자리를 기다리는 시간은 200ms로 줄이고, 가짜 AI가 호출을 붙잡게(CountDownLatch) 해서 "진행 중 2개"를 만든다.
// 횟수 한도는 5로 낮춘다(429가 자리 503보다 먼저인지 보려고). 이 클래스의 테스트는 한 번에 5번 넘게 묻지 않는다.
@SpringBootTest(properties = { "app.chat.rate-limit.per-minute=5", "app.chat.rate-limit.per-day=30", "app.chat.slot-wait=200ms" })
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatConcurrencyTests extends ChatTestBase {

	@Autowired ChatSlots slots;

	// 붙잡힌 AI 호출이 "들어왔다"를 알리는 신호와 "풀어 줘"를 기다리는 문. 누수 방지로 20초를 넘게 기다리지 않는다.
	CountDownLatch 들어옴;
	CountDownLatch 문;
	ExecutorService 풀;

	@BeforeEach
	void 붙잡기_준비() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		풀 = Executors.newFixedThreadPool(3);
	}

	@AfterEach
	void 스레드_정리() throws Exception {
		if (문 != null) {
			문.countDown(); // 혹시 안 풀린 호출이 있으면 풀어 준다
		}
		풀.shutdownNow();
		assertThat(풀.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		assertThat(slots.availablePermits()).as("테스트가 끝나면 자리는 모두 돌아와 있다").isEqualTo(2);
	}

	// 양식 호출(chatJson)이 문이 열릴 때까지 붙잡히게 한다. 앞의 n번 호출만 붙잡고 그 뒤 호출은 바로 통과시킨다.
	private void 앞의_N개_붙잡기(int n) {
		들어옴 = new CountDownLatch(n);
		문 = new CountDownLatch(1);
		AtomicInteger 번째 = new AtomicInteger();
		llm.jsonDoes(() -> {
			if (번째.incrementAndGet() <= n) {
				들어옴.countDown();
				try {
					if (!문.await(20, TimeUnit.SECONDS)) {
						throw new IllegalStateException("문이 안 열림(테스트 누수 방지)");
					}
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(e);
				}
			}
			return "{\"keyword\":\"자전거\"}";
		});
	}

	private Future<MockHttpServletResponse> 질문_비동기() {
		return 풀.submit(() -> 질문("자전거 있어?"));
	}

	@Test
	void 진행_중_질문이_2개면_3번째는_503이고_AI는_부르지_않는다() throws Exception {
		앞의_N개_붙잡기(2);
		Future<MockHttpServletResponse> 첫째 = 질문_비동기();
		Future<MockHttpServletResponse> 둘째 = 질문_비동기();
		assertThat(들어옴.await(10, TimeUnit.SECONDS)).as("두 질문이 AI 호출까지 들어옴").isTrue();
		assertThat(slots.availablePermits()).isZero();

		MockHttpServletResponse 셋째 = 질문("자전거 있어?");

		assertThat(셋째.getStatus()).isEqualTo(503);
		assertThat(llm.jsonCalls).as("3번째 질문의 AI 호출은 0번(붙잡힌 2번뿐)").hasSize(2);
		assertThat(llm.chatCalls).isEmpty();

		문.countDown();
		assertThat(첫째.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
		assertThat(둘째.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
	}

	@Test
	void 붙잡은_2개를_풀면_둘_다_200이고_그_뒤_새_질문은_503이_아니다() throws Exception {
		앞의_N개_붙잡기(2);
		Future<MockHttpServletResponse> 첫째 = 질문_비동기();
		Future<MockHttpServletResponse> 둘째 = 질문_비동기();
		assertThat(들어옴.await(10, TimeUnit.SECONDS)).isTrue();

		문.countDown();

		assertThat(첫째.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
		assertThat(둘째.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
		assertThat(slots.availablePermits()).isEqualTo(2);
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(200);
	}

	@Test
	void 하나만_붙잡혀_있으면_두_번째_질문은_바로_통과한다() throws Exception {
		// "상한은 2"의 아래쪽 경계: 자리 2개 중 1개만 쓰는 중이면 새 질문은 기다리지 않는다
		앞의_N개_붙잡기(1);
		Future<MockHttpServletResponse> 첫째 = 질문_비동기();
		assertThat(들어옴.await(10, TimeUnit.SECONDS)).isTrue();

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(200);

		문.countDown();
		assertThat(첫째.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
	}

	@Test
	void AI가_503_504로_끝나거나_예상_밖_예외가_나도_자리는_돌아온다() throws Exception {
		llm.jsonThrows(new LlmUnavailableException("SECRET"));
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(503);
		assertThat(slots.availablePermits()).as("양식 호출 503 뒤").isEqualTo(2);

		llm.jsonThrows(new LlmTimeoutException("SECRET", null));
		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(504);
		assertThat(slots.availablePermits()).as("양식 호출 504 뒤").isEqualTo(2);

		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.chatThrows(new IllegalArgumentException("버그"));
		// 처리 안 된 예외는 MockMvc에서 그대로 밖으로 나온다(실제 서버에서는 500)
		assertThatThrownBy(() -> 질문("자전거 있어?")).hasRootCauseInstanceOf(IllegalArgumentException.class);
		assertThat(slots.availablePermits()).as("예상 밖 예외(500) 뒤").isEqualTo(2);
	}

	@Test
	void 고정_문구로_끝난_뒤에도_자리는_돌아온다() throws Exception {
		llm.jsonReturns("{\"keyword\":\"\"}"); // 인사: keyword 비어
		assertThat(질문("안녕").getStatus()).isEqualTo(200);
		assertThat(slots.availablePermits()).as("keyword 빔").isEqualTo(2);

		llm.jsonReturns("{\"keyword\":\"없는물건\"}"); // 0건
		MockHttpServletResponse r = 질문("없는물건 있어?");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(llm.chatCalls).isEmpty();
		assertThat(slots.availablePermits()).as("0건").isEqualTo(2);
	}

	@Test
	void 횟수_429가_자리_503보다_먼저다() throws Exception {
		// 한도 5: 붙잡힌 2개 + 자리가 꽉 차 503이 되는 3개 = 5번(503이어도 횟수는 쓰임), 6번째는 자리가 꽉 차 있어도 429
		앞의_N개_붙잡기(2);
		List<Future<MockHttpServletResponse>> 붙잡힘 = new ArrayList<>();
		붙잡힘.add(질문_비동기());
		붙잡힘.add(질문_비동기());
		assertThat(들어옴.await(10, TimeUnit.SECONDS)).isTrue();
		for (int i = 3; i <= 5; i++) {
			assertThat(질문("자전거 있어?").getStatus()).as(i + "번째").isEqualTo(503);
		}

		assertThat(질문("자전거 있어?").getStatus()).as("6번째").isEqualTo(429);
		assertThat(llm.jsonCalls).hasSize(2);

		문.countDown();
		for (Future<MockHttpServletResponse> f : 붙잡힘) {
			assertThat(f.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
		}
	}

	// S17-f2 추가: 이미 중단 신호(interrupt)가 켜진 스레드가 자리를 잡으려 하면 503이고, 신호는 남고, 자리 수는 변하지 않는다.
	@Test
	void 자리_대기가_중단되면_503이고_중단_신호가_남고_자리_수는_그대로다() throws Exception {
		slots.acquire();
		slots.acquire(); // 자리를 모두 쥐어 둔다
		try {
			AtomicReference<Throwable> 예외 = new AtomicReference<>();
			AtomicReference<Boolean> 신호 = new AtomicReference<>();
			Thread t = new Thread(() -> {
				Thread.currentThread().interrupt();
				try {
					slots.acquire();
				} catch (Throwable e) {
					예외.set(e);
				}
				신호.set(Thread.currentThread().isInterrupted());
			});
			t.start();
			t.join(10_000);

			assertThat(예외.get()).isInstanceOf(ResponseStatusException.class);
			assertThat(((ResponseStatusException) 예외.get()).getStatusCode().value()).isEqualTo(503);
			assertThat(신호.get()).as("중단 신호가 되살려져 있다").isTrue();
			assertThat(slots.availablePermits()).as("실패한 acquire는 자리를 바꾸지 않는다").isZero();
		} finally {
			slots.release();
			slots.release();
		}
		assertThat(slots.availablePermits()).isEqualTo(2);
	}
}
