package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.chat.service.ChatRateLimiter;

// S17-f1 추가 검사: 401·403은 세지 않는다 / 동시에 10개를 보내도 정확히 5개만 통과한다(Lua 원자성).
// 가짜 시계(SkewClock)라 테스트 중 분이 안 바뀐다(진짜 시간이 흘러도 분 번호가 그대로라 "같은 분" 검사가 흔들리지 않는다).
@SpringBootTest(properties = { "app.chat.rate-limit.per-minute=5", "app.chat.rate-limit.per-day=30" })
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatRateLimitExtraTests extends ChatTestBase {

	@Autowired ChatRateLimiter limiter;

	@Test
	void 로그인_없는_401과_CSRF_없는_403은_세지_않고_키도_안_만든다() throws Exception {
		for (int i = 0; i < 10; i++) {
			int s401 = mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
					.content(om.writeValueAsString(Map.of("question", "자전거 있어?"))).with(csrf())).andReturn()
					.getResponse().getStatus();
			assertThat(s401).isEqualTo(401);
			int s403 = mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
					.content(om.writeValueAsString(Map.of("question", "자전거 있어?"))).cookie(쿠키)).andReturn()
					.getResponse().getStatus();
			assertThat(s403).isEqualTo(403);
		}

		assertThat(redis.keys("chat-rate-*")).isEmpty();
	}

	@Test
	void 같은_회원이_동시에_10개를_보내도_정확히_5개만_통과한다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(10);
		CountDownLatch ready = new CountDownLatch(10);
		CountDownLatch go = new CountDownLatch(1);
		AtomicInteger ok = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		List<Future<?>> fs = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			fs.add(pool.submit(() -> {
				ready.countDown();
				go.await(); // 전원 준비되면 한꺼번에 출발
				try {
					limiter.countOrReject(777L);
					ok.incrementAndGet();
				} catch (ResponseStatusException e) {
					assertThat(e.getStatusCode().value()).isEqualTo(429);
					rejected.incrementAndGet();
				}
				return null;
			}));
		}
		ready.await();
		go.countDown();
		for (Future<?> f : fs) {
			f.get();
		}
		pool.shutdown();

		assertThat(ok.get()).isEqualTo(5);
		assertThat(rejected.get()).isEqualTo(5);
	}
}
