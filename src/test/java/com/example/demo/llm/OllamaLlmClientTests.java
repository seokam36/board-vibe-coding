package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.demo.llm.service.LlmTimeoutException;
import com.example.demo.llm.service.LlmUnavailableException;
import com.example.demo.llm.service.OffLlmClient;
import com.example.demo.llm.service.OllamaLlmClient;
import com.sun.net.httpserver.HttpServer;

// Ollama 구현이 오류를 우리 예외로 바꾸는지, 재시도 없이 한 번만 시도하는지 가짜 서버로 검사한다 (Ollama·DB 없이 돈다)
class OllamaLlmClientTests {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // 요청 한 건을 받으면 delayMillis 뒤에 status·body로 답하는 가짜 Ollama 서버. 받은 횟수를 센다.
    private AtomicInteger startServer(int status, String body, long delayMillis) throws IOException {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            hits.incrementAndGet();
            try {
                ex.getRequestBody().readAllBytes();
                Thread.sleep(delayMillis);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, bytes.length);
                ex.getResponseBody().write(bytes);
            } catch (Exception ignored) {
                // 클라이언트가 먼저 끊은 경우
            } finally {
                ex.close();
            }
        });
        server.start();
        return hits;
    }

    // 헤더는 바로 보내고, 본문은 여러 조각으로 나눠 totalMillis에 걸쳐 천천히 보내는 가짜 서버(답이 "흘러오는" 경우)
    private AtomicInteger startSlowBodyServer(String body, long totalMillis) throws IOException {
        AtomicInteger hits = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            hits.incrementAndGet();
            try {
                ex.getRequestBody().readAllBytes();
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                int chunks = 6;
                int size = bytes.length / chunks + 1;
                for (int i = 0; i < bytes.length; i += size) {
                    ex.getResponseBody().write(bytes, i, Math.min(size, bytes.length - i));
                    ex.getResponseBody().flush();
                    Thread.sleep(totalMillis / chunks);
                }
            } catch (Exception ignored) {
                // 클라이언트가 먼저 끊은 경우
            } finally {
                ex.close();
            }
        });
        server.start();
        return hits;
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void off_구현은_호출하면_사용_불가_예외() {
        assertThatThrownBy(() -> new OffLlmClient().chat("지시", "질문"))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void 정상_응답이면_답_문자열을_돌려준다() throws IOException {
        startServer(200, "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"안녕\"},\"done\":true}", 0);
        String answer = new OllamaLlmClient(url(), "m", Duration.ofSeconds(5)).chat("지시", "질문");
        assertThat(answer).isEqualTo("안녕");
    }

    @Test
    void 닫힌_포트면_사용_불가_예외() throws IOException {
        // 1번 포트는 아무도 안 듣는 확실히 닫힌 주소다(예전엔 열었다 닫은 포트를 썼는데 그새 다른 프로그램이 잡을 수 있다)
        OllamaLlmClient client = new OllamaLlmClient("http://127.0.0.1:1", "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void 응답이_제한_시간보다_늦으면_시간_초과_예외() throws IOException {
        AtomicInteger hits = startServer(200, "{}", 3000);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmTimeoutException.class);
        assertThat(hits.get()).isEqualTo(1); // 재시도 없이 한 번만
    }

    // 헤더는 바로 오고 본문이 제한 시간(1초)보다 오래(3초) 걸려 흘러와도 제한 근처에서 끊겨야 한다
    @Test
    void 본문이_천천히_흘러와도_제한_시간_근처에서_시간_초과_예외() throws IOException {
        AtomicInteger hits = startSlowBodyServer("{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"안녕\"},\"done\":true}", 3000);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(1));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmTimeoutException.class);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).isLessThan(2500);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 답_내용이_비어_있으면_사용_불가_예외() throws IOException {
        startServer(200, "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"done\":true}", 0);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void 빈_인자는_사용_불가가_아니라_IllegalArgumentException() {
        OllamaLlmClient client = new OllamaLlmClient("http://127.0.0.1:1", "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.chat(null, "질문")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat("지시", " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 서버_오류_응답은_재시도_없이_한_번만_시도하고_사용_불가_예외() throws IOException {
        AtomicInteger hits = startServer(500, "{\"error\":\"boom\"}", 0);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmUnavailableException.class);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 클라이언트_오류_응답도_사용_불가_예외() throws IOException {
        startServer(404, "{\"error\":\"model not found\"}", 0);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmUnavailableException.class);
    }
}
