package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

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
    // 가짜 서버가 마지막으로 받은 요청 본문
    private volatile String lastBody = "";

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
                lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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

    // 실패 메시지용: 예외와 원인 사슬의 클래스 이름을 "A <- B <- C"로 보여 준다(불안정하게 실패할 때 원인을 바로 알 수 있게)
    private static String chain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) {
            sb.append(sb.length() == 0 ? "" : " <- ").append(t.getClass().getName());
        }
        return sb.length() == 0 ? "예외가 없었다" : sb.toString();
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
        Throwable thrown = catchThrowable(() -> client.chat("지시", "질문"));
        assertThat(thrown).withFailMessage("시간 초과 예외여야 하는데 %s", chain(thrown)).isInstanceOf(LlmTimeoutException.class);
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

    // ---- S17-e2: 구조화 호출(chatJson)과 제한 시간을 받는 chat ----

    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"needSearch\":{\"type\":\"boolean\"}},\"required\":[\"needSearch\"]}";

    private static String okBody(String content) {
        return "{\"model\":\"m\",\"created_at\":\"2026-01-01T00:00:00Z\","
                + "\"message\":{\"role\":\"assistant\",\"content\":" + new tools.jackson.databind.ObjectMapper().writeValueAsString(content) + "},\"done\":true}";
    }

    @Test
    void 구조화_호출은_format_모델_think끔을_싣고_JSON_글자를_그대로_돌려준다() throws IOException {
        startServer(200, okBody("{\"needSearch\":true}"), 0);
        String answer = new OllamaLlmClient(url(), "qwen-x", Duration.ofSeconds(5))
                .chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(5));
        assertThat(answer).isEqualTo("{\"needSearch\":true}");
        // 요청 본문을 JSON으로 읽어 구조를 확인한다(글자 포함 여부보다 정확)
        var body = new tools.jackson.databind.ObjectMapper().readTree(lastBody);
        assertThat(body.path("format").path("type").asText()).isEqualTo("object");
        assertThat(body.path("format").path("properties").has("needSearch")).isTrue();
        assertThat(body.path("model").asText()).isEqualTo("qwen-x");
        assertThat(body.path("think").asBoolean(true)).isFalse();
        // 서버가 옵션(temperature 등)을 따로 지정하지 않는다
        assertThat(body.path("options").has("temperature")).isFalse();
    }

    @Test
    void 기존_2인자_chat_요청에는_format이_없다() throws IOException {
        startServer(200, okBody("안녕"), 0);
        new OllamaLlmClient(url(), "m", Duration.ofSeconds(5)).chat("지시", "질문");
        assertThat(lastBody).doesNotContain("\"format\"");
        assertThat(lastBody).contains("\"think\":false");
    }

    @Test
    void 구조화_호출_빈_content_4xx_5xx_서버없음은_사용_불가_예외() throws IOException {
        startServer(200, okBody(""), 0);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(5)))
                .isInstanceOf(LlmUnavailableException.class);
        server.stop(0);
        startServer(404, "{\"error\":\"x\"}", 0);
        OllamaLlmClient c404 = new OllamaLlmClient(url(), "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> c404.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(5)))
                .isInstanceOf(LlmUnavailableException.class);
        server.stop(0);
        startServer(500, "{\"error\":\"x\"}", 0);
        OllamaLlmClient c500 = new OllamaLlmClient(url(), "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> c500.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(5)))
                .isInstanceOf(LlmUnavailableException.class);
        OllamaLlmClient none = new OllamaLlmClient("http://127.0.0.1:1", "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> none.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(5)))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void 구조화_호출은_넘긴_제한_시간_근처에서_시간_초과_예외_재시도_없음() throws IOException {
        AtomicInteger hits = startServer(200, "{}", 3000);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(10));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(1)))
                .isInstanceOf(LlmTimeoutException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void chat_겹치기도_넘긴_제한_시간에서_끊긴다() throws IOException {
        AtomicInteger hits = startServer(200, "{}", 3000);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(10));
        long start = System.nanoTime();
        assertThatThrownBy(() -> client.chat("지시", "질문", Duration.ofSeconds(1)))
                .isInstanceOf(LlmTimeoutException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
        assertThat(hits.get()).isEqualTo(1);
    }

    // 헤더는 바로 오고 본문만 느린 서버를 쓴다. 헤더가 늦은 서버는 넘긴 값(30초)이 아니라 응답 지연 때문에 판별이 흐려질 수 있어, 본문만 느린 서버로 min 줄만 시험한다.
    // 본문 3.5초 + 넘긴 값 30초 + 생성자 1초 -> min(30, 1)=1초에서 끊겨야 한다(min이 없으면 3.5초 뒤 정상 응답이라 실패).
    @Test
    void 넘긴_제한_시간이_생성자_제한보다_길면_생성자_제한에서_끊긴다() throws IOException {
        startSlowBodyServer(okBody("안녕"), 3500);
        OllamaLlmClient client = new OllamaLlmClient(url(), "m", Duration.ofSeconds(1));
        long start = System.nanoTime();
        Throwable thrown = catchThrowable(() -> client.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(30)));
        assertThat(thrown).withFailMessage("시간 초과 예외여야 하는데 %s", chain(thrown)).isInstanceOf(LlmTimeoutException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
    }

    // Spring AI가 스키마를 바로 JSON으로 파싱하므로, JSON이 아닌 스키마는 IllegalArgumentException으로 통일한다(원문은 메시지에 없음)
    @Test
    void JSON이_아닌_스키마는_IllegalArgumentException() {
        OllamaLlmClient client = new OllamaLlmClient("http://127.0.0.1:1", "m", Duration.ofSeconds(5));
        assertThatThrownBy(() -> client.chatJson("지시", "질문", "{", Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("jsonSchema가 올바른 JSON이 아닙니다.");
    }

    @Test
    void 잘못된_인자는_IllegalArgumentException() {
        OllamaLlmClient client = new OllamaLlmClient("http://127.0.0.1:1", "m", Duration.ofSeconds(5));
        Duration ok = Duration.ofSeconds(1);
        assertThatThrownBy(() -> client.chatJson("지시", "질문", SCHEMA, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chatJson("지시", "질문", SCHEMA, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chatJson("지시", "질문", null, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chatJson("지시", "질문", " ", ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chatJson("", "질문", SCHEMA, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chatJson("지시", null, SCHEMA, ok)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat("지시", "질문", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.chat("지시", "질문", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void off_구현의_새_메서드는_인자와_무관하게_사용_불가_예외() {
        OffLlmClient off = new OffLlmClient();
        assertThatThrownBy(() -> off.chatJson(null, null, null, null)).isInstanceOf(LlmUnavailableException.class);
        assertThatThrownBy(() -> off.chat("지시", "질문", Duration.ofSeconds(1))).isInstanceOf(LlmUnavailableException.class);
    }
}
