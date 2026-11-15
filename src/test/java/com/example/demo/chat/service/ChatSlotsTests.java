package com.example.demo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.example.demo.config.ChatProperties;

// ChatSlots 단위 테스트(스프링 없이). S17-f2 리뷰 반영: "먼저 줄 선 질문이 먼저 자리를 받는다"(공정 모드).
class ChatSlotsTests {

	@Test
	void 먼저_기다린_질문이_먼저_자리를_받는다() throws Exception {
		// 자리 1개, 기다리는 시간은 넉넉히 10초(테스트가 끝나기 전에 시간 초과로 503이 되지 않게)
		ChatSlots slots = new ChatSlots(new ChatProperties(new ChatProperties.RateLimit(5, 30), new ChatProperties.GlobalLimit(0, 0), 1, Duration.ofSeconds(10)));
		List<String> 받은_순서 = new CopyOnWriteArrayList<>();
		CountDownLatch 첫째_받음 = new CountDownLatch(1);
		CountDownLatch 첫째_놓아도_됨 = new CountDownLatch(1);

		slots.acquire(); // 자리를 먼저 쥐어 둔다(질문 A·B가 줄을 서게)

		Thread 첫째 = new Thread(() -> {
			slots.acquire();
			받은_순서.add("A");
			첫째_받음.countDown();
			try {
				첫째_놓아도_됨.await(10, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			slots.release();
		});
		Thread 둘째 = new Thread(() -> {
			slots.acquire();
			받은_순서.add("B");
			slots.release();
		});
		try {
			첫째.start();
			줄_설_때까지_기다린다(첫째); // A가 확실히 먼저 줄을 선 뒤에
			둘째.start();
			줄_설_때까지_기다린다(둘째); // B가 줄을 선다

			slots.release(); // 자리가 하나 난다 -> 먼저 선 A가 받아야 한다
			assertThat(첫째_받음.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(받은_순서).as("A가 자리를 쥐고 있는 동안 B는 받지 못한다").containsExactly("A");

			첫째_놓아도_됨.countDown(); // A가 놓으면 B 차례
			첫째.join(10_000);
			둘째.join(10_000);
			assertThat(받은_순서).containsExactly("A", "B");
			assertThat(slots.availablePermits()).isEqualTo(1);
		} finally {
			첫째_놓아도_됨.countDown();
			첫째.interrupt();
			둘째.interrupt();
		}
	}

	// 스레드가 acquire 안에서 시간 제한 대기(TIMED_WAITING)에 들어갈 때까지 기다린다(최대 5초).
	private static void 줄_설_때까지_기다린다(Thread t) throws InterruptedException {
		long 한계 = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (t.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < 한계) {
			Thread.sleep(5);
		}
		assertThat(t.getState()).isEqualTo(Thread.State.TIMED_WAITING);
	}
}
