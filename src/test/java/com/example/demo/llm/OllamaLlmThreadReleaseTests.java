package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.demo.llm.service.LlmTimeoutException;
import com.example.demo.llm.service.OllamaLlmClient;
import com.sun.net.httpserver.HttpServer;

// 시간 초과 뒤에 백그라운드 스레드("ollama-call")가 풀려나는지, 서버 쪽 연결이 끊기는지 검사한다.
// 가짜 서버가 본문을 끝없이 흘려보내는 최악의 경우를 쓴다.
class OllamaLlmThreadReleaseTests {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    // 풀에서 놀고 있는 스레드(최대 60초 대기)는 제외하고, 실제로 호출(chat)을 처리 중인 스레드만 센다
    private static long ollamaCallThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("ollama-call") && t.isAlive()
                        && java.util.Arrays.stream(t.getStackTrace()).anyMatch(f -> f.getClassName().contains("OllamaChatModel") || f.getClassName().contains("RestClient"))).count();
    }

    @Test
    void 시간_초과_뒤_끝없이_흐르는_본문을_읽던_스레드가_풀리고_서버_연결이_끊긴다() throws Exception {
        AtomicBoolean serverWriteFailed = new AtomicBoolean(false);
        CountDownLatch writeFailedLatch = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", ex -> {
            try {
                ex.getRequestBody().readAllBytes();
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, 0); // 길이 모름 = 끝없이 보낼 수 있는 방식
                byte[] junk = "{\"x\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}".getBytes();
                while (true) { // 끝없이 흘려보낸다. 클라이언트가 끊으면 쓰기가 예외로 끝난다.
                    ex.getResponseBody().write(junk);
                    ex.getResponseBody().flush();
                    Thread.sleep(20);
                }
            } catch (Exception e) {
                serverWriteFailed.set(true);
                writeFailedLatch.countDown();
            } finally {
                ex.close();
            }
        });
        server.start();

        long before = ollamaCallThreads();
        OllamaLlmClient client = new OllamaLlmClient(
                "http://127.0.0.1:" + server.getAddress().getPort(), "m", Duration.ofSeconds(1));
        assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmTimeoutException.class);

        // 시간 초과 후 5초 안에 서버가 연결 끊김을 알아채야 한다
        boolean disconnected = writeFailedLatch.await(5, TimeUnit.SECONDS);
        // 스레드도 5초 안에 사라져야 한다
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ollamaCallThreads() > before && System.nanoTime() < deadline) Thread.sleep(100);

        assertThat(disconnected).as("서버 쪽 연결이 5초 안에 끊겨야 한다").isTrue();
        assertThat(ollamaCallThreads()).as("남은 ollama-call 스레드 수").isLessThanOrEqualTo(before);
    }
}
