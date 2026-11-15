package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.demo.llm.service.LlmTimeoutException;
import com.example.demo.llm.service.OllamaLlmClient;
import com.sun.net.httpserver.HttpServer;

// S17-e2 보강 검사: chatJson의 스레드 해제, 같은 클라이언트 연속 호출 때 format 새지 않음, chat 3인자의 잘못된 인자
class OllamaChatJsonExtraTests {

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"},\"maxPrice\":{\"type\":\"integer\"}}}"; // 챗봇 양식과 같은 모양
    private static final String OK = "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
            + "\"message\":{\"role\":\"assistant\",\"content\":\"{}\"},\"done\":true}";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static long ollamaCallThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("ollama-call") && t.isAlive()
                        && java.util.Arrays.stream(t.getStackTrace()).anyMatch(f -> f.getClassName().contains("OllamaChatModel") || f.getClassName().contains("RestClient"))).count();
    }

    // 무엇을: chatJson 시간 초과 뒤 끝없이 흐르는 본문을 읽던 스레드가 풀리고 서버 연결이 끊기는지. 왜: chatJson도 같은 call()을 쓰므로 누수가 없어야 한다
    @Test
    void chatJson_시간_초과_뒤_스레드가_풀리고_서버_연결이_끊긴다() throws Exception {
        CountDownLatch writeFailed = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            try {
                ex.getRequestBody().readAllBytes();
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, 0);
                byte[] junk = "{\"x\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}".getBytes();
                while (true) {
                    ex.getResponseBody().write(junk);
                    ex.getResponseBody().flush();
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                writeFailed.countDown();
            } finally {
                ex.close();
            }
        });
        server.start();

        long before = ollamaCallThreads();
        OllamaLlmClient client = new OllamaLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "m", Duration.ofSeconds(10));
        assertThatThrownBy(() -> client.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(1)))
                .isInstanceOf(LlmTimeoutException.class);

        boolean disconnected = writeFailed.await(5, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ollamaCallThreads() > before && System.nanoTime() < deadline) Thread.sleep(100);

        assertThat(disconnected).as("서버 쪽 연결이 5초 안에 끊겨야 한다").isTrue();
        assertThat(ollamaCallThreads()).as("남은 ollama-call 스레드 수").isLessThanOrEqualTo(before);
    }

    // 무엇을: 같은 클라이언트로 chatJson -> chat -> chatJson 순서로 부를 때 요청별 format 유무. 왜: 호출 옵션이 기본 옵션에 새면 일반 질문이 JSON 모양으로 강제된다
    @Test
    void 같은_클라이언트_연속_호출에서_format이_새지_않는다() throws IOException {
        List<String> bodies = new CopyOnWriteArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            try {
                bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] bytes = OK.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                ex.getResponseBody().write(bytes);
            } finally {
                ex.close();
            }
        });
        server.start();
        OllamaLlmClient client = new OllamaLlmClient("http://127.0.0.1:" + server.getAddress().getPort(), "m", Duration.ofSeconds(5));

        client.chatJson("지시", "질문1", SCHEMA, Duration.ofSeconds(5));
        client.chat("지시", "질문2", Duration.ofSeconds(5));
        client.chat("지시", "질문3", Duration.ofSeconds(5));
        client.chatJson("지시", "질문4", SCHEMA, Duration.ofSeconds(5));
        client.chat("지시", "질문5", Duration.ofSeconds(5));

        List<Boolean> hasFormat = new ArrayList<>();
        for (String b : bodies) hasFormat.add(b.contains("\"format\""));
        assertThat(hasFormat).containsExactly(true, false, false, true, false);
        // 모든 요청이 think 끔·모델 이름을 유지
        assertThat(bodies).allSatisfy(b -> assertThat(b).contains("\"think\":false").contains("\"model\":\"m\""));
    }

    // 무엇을: chat 3인자의 잘못된 timeout(음수)과 빈 프롬프트. 왜: 완료 조건 6
    @Test
    void chat_3인자의_음수_timeout과_빈_프롬프트는_IllegalArgumentException() {
        OllamaLlmClient client = new OllamaLlmClient("http://127.0.0.1:1", "m", Duration.ofSeconds(5));
        Duration ok = Duration.ofSeconds(1);
        assertThatThrownBy(() -> client.chat("지시", "질문", Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat("", "질문", ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat("지시", null, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat(null, "질문", ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chatJson("지시", " ", SCHEMA, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chatJson(" ", "질문", SCHEMA, ok)).isInstanceOf(IllegalArgumentException.class);
    }
}
