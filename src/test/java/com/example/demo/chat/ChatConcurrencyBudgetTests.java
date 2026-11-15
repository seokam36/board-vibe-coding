package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.chat.service.ChatSlots;

// S17-f2: 자리 대기에 쓴 시간이 질문 전체 60초 예산에서 빠지는지.
// 진짜로 기다리지 않는다: 3번째 질문이 자리를 기다리는 동안(hasWaiting) 가짜 시계를 10초 앞으로 돌리고 자리를 풀어 준다.
// 시작 시각을 자리 대기 "전"에 잡았다면 양식 호출은 60 - 10 = 50초를 받는다(대기 "후"에 잡았다면 60초를 받는다).
// 대기 시간(기본 5초)은 그대로 둔다: 3번째 질문이 줄 서고 풀어 줄 때까지 몇 ms면 되므로 여유가 크다.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatConcurrencyBudgetTests extends ChatTestBase {

	@Autowired ChatSlots slots;
	@Autowired ScriptedLlmClient.SkewClock clock;

	ExecutorService 풀;
	CountDownLatch 문;

	@AfterEach
	void 스레드_정리() throws Exception {
		if (문 != null) {
			문.countDown();
		}
		if (풀 != null) {
			풀.shutdownNow();
			assertThat(풀.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(slots.availablePermits()).isEqualTo(2);
	}

	@Test
	void 자리를_기다린_10초는_양식_호출과_답_호출의_제한_시간에서_빠진다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		풀 = Executors.newFixedThreadPool(3);
		CountDownLatch 들어옴 = new CountDownLatch(2);
		문 = new CountDownLatch(1);
		AtomicInteger 번째 = new AtomicInteger();
		llm.jsonDoes(() -> {
			if (번째.incrementAndGet() <= 2) { // 앞의 2개만 붙잡는다
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
		풀.submit(() -> 질문("자전거 있어?"));
		풀.submit(() -> 질문("자전거 있어?"));
		assertThat(들어옴.await(10, TimeUnit.SECONDS)).isTrue();

		Future<MockHttpServletResponse> 셋째 = 풀.submit(() -> 질문("자전거 있어?"));
		long 한계 = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
		while (!slots.hasWaiting() && System.nanoTime() < 한계) {
			Thread.sleep(5); // 3번째 질문이 자리 줄에 설 때까지(짧게) 기다린다
		}
		assertThat(slots.hasWaiting()).as("3번째 질문이 자리를 기다리는 중").isTrue();
		clock.advance(Duration.ofSeconds(10)); // 자리를 기다리며 10초가 흘렀다고 치자
		문.countDown(); // 붙잡힌 둘을 풀면 자리가 나서 3번째가 들어간다

		assertThat(셋째.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
		// 호출 3개 중 3번째로 들어온 양식 호출(붙잡히지 않은 것)의 제한 시간: 60 - 10 = 50초
		assertThat(llm.jsonCalls.stream().map(ScriptedLlmClient.Call::timeout)).contains(Duration.ofSeconds(50));
		assertThat(llm.jsonCalls.stream().filter(c -> c.timeout().equals(Duration.ofSeconds(60))).count())
				.as("앞의 두 질문은 기다린 적이 없어 60초").isEqualTo(2);
		// 답 호출 3개 전부 50초다: 가짜 시계가 10초 간 뒤라 앞의 두 질문도 (시작부터 10초 경과) 50초를 받는다. 그래서 이 단언만으로는 "3번째가 자리 대기를 뺐다"의 증명이 아니고(그건 위 양식 호출 50초 단언이 한다), "답 호출이 예산을 이어 쓰며 3개 모두 줄었다"만 확인한다.
		assertThat(llm.chatCalls).as("답 호출 3개").hasSize(3);
		assertThat(llm.chatCalls.stream().map(ScriptedLlmClient.Call::timeout)).containsOnly(Duration.ofSeconds(50));
	}

	// S17-f2 추가: 자리를 기다리다 60초 예산을 다 쓰면(남은 0 이하) AI를 부르지 않고 504, 자리는 돌아온다(@AfterEach가 2 확인).
	@Test
	void 자리_대기로_예산을_다_쓰면_AI를_부르지_않고_504이다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		풀 = Executors.newFixedThreadPool(3);
		CountDownLatch 들어옴 = new CountDownLatch(2);
		문 = new CountDownLatch(1);
		AtomicInteger 번째 = new AtomicInteger();
		llm.jsonDoes(() -> {
			if (번째.incrementAndGet() <= 2) {
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
		풀.submit(() -> 질문("자전거 있어?"));
		풀.submit(() -> 질문("자전거 있어?"));
		assertThat(들어옴.await(10, TimeUnit.SECONDS)).isTrue();

		Future<MockHttpServletResponse> 셋째 = 풀.submit(() -> 질문("자전거 있어?"));
		long 한계 = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
		while (!slots.hasWaiting() && System.nanoTime() < 한계) {
			Thread.sleep(5);
		}
		assertThat(slots.hasWaiting()).isTrue();
		clock.advance(Duration.ofSeconds(60)); // 기다리는 사이 예산 60초를 전부 썼다고 치자
		문.countDown();

		assertThat(셋째.get(15, TimeUnit.SECONDS).getStatus()).isEqualTo(504);
		assertThat(llm.jsonCalls).as("3번째 질문은 AI를 부르지 않음(붙잡힌 2번뿐)").hasSize(2);
	}

	// S17-f2 추가: 자리를 기다리는 "도중" 중단(interrupt)되면 503, 신호 유지, 자리 수 변화 없음.
	@Test
	void 자리를_기다리는_도중_중단되면_503이고_중단_신호가_남는다() throws Exception {
		slots.acquire();
		slots.acquire();
		try {
			AtomicReference<Throwable> 예외 = new AtomicReference<>();
			AtomicReference<Boolean> 신호 = new AtomicReference<>();
			Thread t = new Thread(() -> {
				try {
					slots.acquire();
				} catch (Throwable e) {
					예외.set(e);
				}
				신호.set(Thread.currentThread().isInterrupted());
			});
			t.start();
			long 한계 = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
			while (!slots.hasWaiting() && System.nanoTime() < 한계) {
				Thread.sleep(5);
			}
			assertThat(slots.hasWaiting()).isTrue();
			t.interrupt();
			t.join(10_000);

			assertThat(예외.get()).isInstanceOf(ResponseStatusException.class);
			assertThat(((ResponseStatusException) 예외.get()).getStatusCode().value()).isEqualTo(503);
			assertThat(신호.get()).isTrue();
			assertThat(slots.availablePermits()).isZero();
		} finally {
			slots.release();
			slots.release();
		}
		assertThat(slots.availablePermits()).isEqualTo(2);
	}
}
