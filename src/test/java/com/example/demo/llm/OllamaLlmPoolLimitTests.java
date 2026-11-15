package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.demo.config.LlmConfig;
import com.example.demo.llm.service.LlmClient;
import com.example.demo.llm.service.LlmTimeoutException;
import com.example.demo.llm.service.LlmUnavailableException;
import com.example.demo.llm.service.OllamaLlmClient;
import com.sun.net.httpserver.HttpServer;

// S17-f3: "ollama-call" 스레드 풀 상한. 자리가 꽉 차면 줄 세우지 않고 바로 사용 불가로 알리는지 검사한다.
class OllamaLlmPoolLimitTests {

    private static final String OK_BODY = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"안녕\"},\"done\":true}";

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    // true인 동안 들어온 요청은 가짜 서버가 응답을 안 주고 release 래치가 열릴 때까지 붙들고 있는다(응답 없는 Ollama 흉내).
    // 요청이 들어온 순간의 값으로 정하므로, false로 바꾼 뒤 온 새 요청은 바로 답한다.
    private final AtomicBoolean hang = new AtomicBoolean(true);
    // 붙들린 요청을 테스트 끝에서 한꺼번에 풀어 주는 래치(시간으로 풀리면 취소가 아무 일 안 해도 통과해 버리기 때문)
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService serverExecutor = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) server.stop(0);
        serverExecutor.shutdownNow();
    }

    private String startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 기본 실행기는 스레드 1개라 붙들린 요청 하나가 나머지를 막는다. 요청마다 스레드를 쓰게 한다.
        server.setExecutor(serverExecutor);
        server.createContext("/api/chat", ex -> {
            hits.incrementAndGet();
            boolean hung = hang.get();
            try {
                ex.getRequestBody().readAllBytes();
                if (hung) release.await();
                byte[] bytes = OK_BODY.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (Exception ignored) {
                // 클라이언트가 먼저 끊은 경우
            } finally {
                ex.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static long aliveOllamaCallThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("ollama-call") && t.isAlive()).count();
    }

    @Test
    void 상한만큼_걸려_있으면_다음_호출은_기다리지_않고_사용_불가_그리고_스레드는_상한_이하() throws Exception {
        int max = 2;
        // 제한 시간을 넉넉히(10초) 둔다: 느린 PC·첫 클래스 로딩에도 단언을 끝낼 때까지 시간 초과가 먼저 나지 않게
        OllamaLlmClient client = new OllamaLlmClient(startServer(), "m", Duration.ofSeconds(10), max);
        long before = aliveOllamaCallThreads();

        // 상한(2)만큼 호출을 걸어 둔다(각자 별도 스레드에서, 서버가 래치로 붙들고 있다)
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            Thread t = new Thread(() -> {
                try {
                    client.chat("지시", "질문");
                } catch (RuntimeException ignored) {
                    // 단언이 끝난 뒤 풀려 정상 답이 오거나, 정리 중 끊겨도 상관없다
                }
            });
            t.start();
            callers.add(t);
        }
        long wait = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (hits.get() < max && System.nanoTime() < wait) Thread.sleep(20);
        assertThat(hits.get()).as("가짜 서버에 걸린 호출 수").isEqualTo(max);

        long start = System.nanoTime();
        assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("거절은 대기 없이 바로").isLessThan(Duration.ofSeconds(1));

        assertThat(aliveOllamaCallThreads() - before).as("새로 생긴 ollama-call 스레드 수").isLessThanOrEqualTo(max);
        assertThat(hits.get()).as("거절된 호출은 서버까지 가지 않는다").isEqualTo(max);

        // 단언이 끝났으니 붙들린 요청을 풀어 호출 스레드들이 정리되게 한다
        release.countDown();
        for (Thread t : callers) t.join(10_000);
    }

    @Test
    void 걸려_있던_호출이_시간_초과로_끝나면_새_호출은_정상_답을_받는다() throws Exception {
        int max = 2;
        OllamaLlmClient client = new OllamaLlmClient(startServer(), "m", Duration.ofSeconds(1), max);

        // 상한만큼 호출이 걸렸다가 1초 뒤 모두 시간 초과로 끝난다
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < max; i++) {
            Thread t = new Thread(() -> {
                try {
                    client.chat("지시", "질문");
                } catch (LlmTimeoutException ignored) {
                    // 기대한 시간 초과
                }
            });
            t.start();
            callers.add(t);
        }
        for (Thread t : callers) t.join(10_000);

        // 걸린 요청은 래치로 테스트 끝까지 붙들려 있다(서버가 스스로 풀어 주지 않는다). 새 요청만 바로 답한다.
        // 그러므로 정상 답이 오려면 cancel(true)가 붙들린 호출 스레드를 실제로 풀어 줬어야 한다.
        // 취소된 스레드가 풀로 돌아오는 데 아주 잠깐 걸릴 수 있어(그래서 상한에 여유 2를 둔다) 2초 안에 오는지를 본다.
        hang.set(false);
        String answer = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (answer == null && System.nanoTime() < deadline) {
            try {
                answer = client.chat("지시", "질문");
            } catch (LlmUnavailableException busy) {
                Thread.sleep(50);
            }
        }
        assertThat(answer).as("취소가 스레드를 풀어 줬다면 2초 안에 정상 답").isEqualTo("안녕");
        assertThat(hits.get()).as("걸린 2개 + 새 호출").isGreaterThan(max);
    }

    @Test
    void LlmConfig는_동시_질문_상한에_2를_더한_값을_호출_스레드_상한으로_쓴다() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LlmConfig.class)
                .withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.base-url=http://127.0.0.1:1");
        runner.run(ctx -> assertThat(((OllamaLlmClient) ctx.getBean(LlmClient.class)).maxCallThreads()).isEqualTo(4));
        runner.withPropertyValues("app.chat.max-concurrent=5")
                .run(ctx -> assertThat(((OllamaLlmClient) ctx.getBean(LlmClient.class)).maxCallThreads()).isEqualTo(7));
    }

    @Test
    void 호출_스레드_상한이_0이나_음수면_IllegalArgumentException() {
        // 상한이 1 미만이면 호출할 자리가 없으므로 만들 때 바로 막는다
        assertThatThrownBy(() -> new OllamaLlmClient("http://127.0.0.1:1", "m", Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OllamaLlmClient("http://127.0.0.1:1", "m", Duration.ofSeconds(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ollama_call_스레드는_데몬이라_종료를_막지_않는다() throws Exception {
        hang.set(false);
        OllamaLlmClient client = new OllamaLlmClient(startServer(), "m", Duration.ofSeconds(2), 2);
        assertThat(client.chat("지시", "질문")).isEqualTo("안녕");
        // 방금 호출로 만들어진 스레드가 있으면 전부 데몬이어야 한다(코어 0 + 60초 유휴 후 소멸)
        var threads = Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("ollama-call") && t.isAlive()).toList();
        assertThat(threads).isNotEmpty().allMatch(Thread::isDaemon);
    }
}
