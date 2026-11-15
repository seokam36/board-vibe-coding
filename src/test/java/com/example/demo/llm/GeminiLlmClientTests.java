package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
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

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.example.demo.llm.service.GeminiLlmClient;
import com.example.demo.llm.service.LlmTimeoutException;
import com.example.demo.llm.service.LlmUnavailableException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 제미나이 구현을 가짜 서버(JDK HttpServer)로 검사한다. 실제 제미나이·DB 없이 돈다.
class GeminiLlmClientTests {

    private static final String KEY = "KEY-12345-SECRET";
    private static final String KEY_PART = KEY.substring(0, 8); // 오류 본문에 키 일부만 섞여 와도 새면 안 된다
    private static final String ERROR_MESSAGE = "ERRMSG-LEAK-CHECK";
    private static final String MODEL = "gemini-test";
    private static final String SCHEMA = "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"}}}";
    private static final Duration FIVE = Duration.ofSeconds(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    // 가짜 서버가 마지막으로 받은 요청
    private volatile String lastMethod;
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile String lastKeyHeader;
    private volatile String lastContentType;
    private volatile String lastBody = "";
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService serverExecutor = Executors.newCachedThreadPool();

    private ListAppender<ILoggingEvent> logs;
    private Logger clientLogger;

    @BeforeEach
    void captureLogs() {
        clientLogger = (Logger) LoggerFactory.getLogger(GeminiLlmClient.class);
        logs = new ListAppender<>();
        logs.start();
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        release.countDown();
        clientLogger.detachAppender(logs);
        if (server != null) server.stop(0);
        serverExecutor.shutdownNow();
    }

    // ---- 가짜 서버 ----

    // 가짜 서버가 요청마다 하는 일(응답 방식)만 다른 테스트가 직접 정한다
    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    // 모든 가짜 서버의 공통 틀: 받은 요청을 기록하고(횟수·메서드·경로·쿼리·키 헤더·본문), 응답은 handler에게 맡긴다
    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor); // 붙들린 요청 하나가 나머지를 막지 않게 요청마다 스레드
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            lastMethod = ex.getRequestMethod();
            lastPath = ex.getRequestURI().getPath();
            lastQuery = ex.getRequestURI().getRawQuery();
            lastKeyHeader = ex.getRequestHeaders().getFirst("x-goog-api-key");
            lastContentType = ex.getRequestHeaders().getFirst("Content-Type");
            try {
                lastBody = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                handler.handle(ex);
            } catch (Exception ignored) {
                // 클라이언트가 먼저 끊은 경우
            } finally {
                ex.close();
            }
        });
        server.start();
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    // status·body로 바로 답하는 서버
    private void startServer(int status, String body) throws IOException {
        startServer(ex -> reply(ex, status, body));
    }

    // 래치가 열릴 때까지 응답을 안 주고 붙드는 서버(응답 없는 제미나이 흉내)
    private void startHangingServer() throws IOException {
        startServer(ex -> {
            release.await();
            reply(ex, 200, ok("x"));
        });
    }

    // 헤더는 바로, 본문은 조각내 totalMillis에 걸쳐 천천히 보내는 서버
    private void startSlowBodyServer(String body, long totalMillis) throws IOException {
        startServer(ex -> {
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
        });
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private GeminiLlmClient client() {
        return new GeminiLlmClient(url(), KEY, MODEL, FIVE, 4);
    }

    private static String ok(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + json(text)
                + "}]},\"finishReason\":\"STOP\"}]}";
    }

    private static String json(String s) {
        return new ObjectMapper().writeValueAsString(s);
    }

    // ---- 비밀이 새는지 보는 도구 ----

    // 예외와 원인 사슬 전체의 클래스 이름·메시지를 한 글자로 모은다
    private static String everything(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(' ').append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }

    private String logText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logs.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) sb.append(e.getThrowableProxy().getMessage()).append('\n');
        }
        return sb.toString();
    }

    private void assertNoSecrets(Throwable thrown) {
        String all = everything(thrown) + logText();
        assertThat(all).doesNotContain(KEY).doesNotContain(KEY_PART).doesNotContain("x-goog-api-key")
                .doesNotContain(ERROR_MESSAGE).doesNotContain("secretquery");
    }

    // 잘못된 인자: IllegalArgumentException이고 메시지에 키가 없다
    private static void assertRejected(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(KEY);
    }

    // ---- 생성자 ----

    @Test
    void 잘못된_생성자_인자는_IllegalArgumentException_메시지에_키_없음() {
        String u = "http://127.0.0.1:1";
        assertRejected(() -> new GeminiLlmClient(" ", KEY, MODEL, FIVE, 1));
        assertRejected(() -> new GeminiLlmClient(null, KEY, MODEL, FIVE, 1));
        assertRejected(() -> new GeminiLlmClient(u, "", MODEL, FIVE, 1));
        assertRejected(() -> new GeminiLlmClient(u, null, MODEL, FIVE, 1));
        assertRejected(() -> new GeminiLlmClient(u, KEY, " ", FIVE, 1));
        assertRejected(() -> new GeminiLlmClient(u, KEY, MODEL, Duration.ZERO, 1));
        assertRejected(() -> new GeminiLlmClient(u, KEY, MODEL, Duration.ofSeconds(-1), 1));
        assertRejected(() -> new GeminiLlmClient(u, KEY, MODEL, null, 1));
        assertRejected(() -> new GeminiLlmClient(u, KEY, MODEL, FIVE, 0));
    }

    @Test
    void 키에_줄바꿈_같은_제어_글자가_있으면_만들_때_IllegalArgumentException() {
        String u = "http://127.0.0.1:1";
        assertRejected(() -> new GeminiLlmClient(u, KEY + "\n", MODEL, FIVE, 1));
        assertRejected(() -> new GeminiLlmClient(u, "AB\rCD" + KEY, MODEL, FIVE, 1));
        assertRejected(() -> new GeminiLlmClient(u, KEY + "\u0000", MODEL, FIVE, 1));
    }

    @Test
    void 모델_이름이_경로를_바꿀_수_있으면_IllegalArgumentException() {
        String u = "http://127.0.0.1:1";
        for (String bad : new String[] {"models/x", "a/b", "a?b", "a b", "a#b", "a:b"}) {
            assertRejected(() -> new GeminiLlmClient(u, KEY, bad, FIVE, 1));
        }
        // 실제로 쓸 이름 모양은 통과
        assertThat(new GeminiLlmClient(u, KEY, "gemini-3.5-flash-lite", FIVE, 1)).isNotNull();
    }

    @Test
    void baseUrl에_쿼리나_해시가_있거나_주소가_깨졌으면_값_없는_메시지의_IllegalArgumentException() {
        assertRejected(() -> new GeminiLlmClient("http://127.0.0.1:1/?secretquery=1", KEY, MODEL, FIVE, 1));
        assertRejected(() -> new GeminiLlmClient("http://127.0.0.1:1#secretquery", KEY, MODEL, FIVE, 1));
        Throwable broken = catchThrowable(() -> new GeminiLlmClient("http://exa mple secretquery", KEY, MODEL, FIVE, 1));
        assertThat(broken).isInstanceOf(IllegalArgumentException.class);
        assertThat(everything(broken)).doesNotContain("secretquery").doesNotContain(KEY);
    }

    @Test
    void isEnabled는_true_toString에는_키가_없다() {
        GeminiLlmClient c = new GeminiLlmClient("http://127.0.0.1:1", KEY, MODEL, FIVE, 2);
        assertThat(c.isEnabled()).isTrue();
        assertThat(c.toString()).doesNotContain(KEY);
    }

    // ---- 요청 모양 ----

    @Test
    void chat은_POST_경로_키_헤더_본문_모양이_맞고_parts를_이어_붙여_돌려준다() throws IOException {
        startServer(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"안\"},{\"text\":\"녕\"}]},\"finishReason\":\"STOP\"}]}");
        String answer = client().chat("지시문", "질문", FIVE);

        assertThat(answer).isEqualTo("안녕");
        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/v1beta/models/" + MODEL + ":generateContent");
        assertThat(lastKeyHeader).isEqualTo(KEY);
        assertThat(lastQuery).as("키는 주소(쿼리)에 없어야 한다").isNull();
        assertThat(lastContentType).startsWith("application/json");
        assertThat(lastBody).doesNotContain(KEY);

        JsonNode body = mapper.readTree(lastBody);
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asString()).isEqualTo("지시문");
        assertThat(body.path("contents").get(0).path("role").asString()).isEqualTo("user");
        assertThat(body.path("contents").get(0).path("parts").get(0).path("text").asString()).isEqualTo("질문");
        assertThat(body.path("generationConfig").path("thinkingConfig").path("thinkingLevel").asString()).isEqualTo("minimal");
        assertThat(body.path("generationConfig").has("responseMimeType")).isFalse();
        assertThat(body.path("generationConfig").has("responseJsonSchema")).isFalse();
    }

    @Test
    void chatJson은_responseMimeType과_스키마를_JSON_객체로_싣는다() throws IOException {
        startServer(200, ok("{\"keyword\":\"자전거\"}"));
        String answer = client().chatJson("지시문", "질문", SCHEMA, FIVE);

        assertThat(answer).isEqualTo("{\"keyword\":\"자전거\"}");
        JsonNode config = mapper.readTree(lastBody).path("generationConfig");
        assertThat(config.path("responseMimeType").asString()).isEqualTo("application/json");
        assertThat(config.path("responseJsonSchema").isObject()).as("문자열이 아니라 객체").isTrue();
        assertThat(config.path("responseJsonSchema").path("type").asString()).isEqualTo("object");
        assertThat(config.path("thinkingConfig").path("thinkingLevel").asString()).isEqualTo("minimal");
        assertThat(lastKeyHeader).isEqualTo(KEY);
        assertThat(lastQuery).isNull();
    }

    @Test
    void baseUrl_끝_슬래시는_무시한다() throws IOException {
        startServer(200, ok("응"));
        String answer = new GeminiLlmClient(url() + "/", KEY, MODEL, FIVE, 1).chat("지시", "질문", FIVE);
        assertThat(answer).isEqualTo("응");
        assertThat(lastPath).isEqualTo("/v1beta/models/" + MODEL + ":generateContent");
    }

    @Test
    void JSON_객체가_아닌_스키마는_호출_전에_IllegalArgumentException() throws IOException {
        startServer(200, ok("x"));
        GeminiLlmClient c = client();
        assertThatThrownBy(() -> c.chatJson("지시", "질문", "{", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chatJson("지시", "질문", "[1,2]", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chatJson("지시", "질문", "\"abc\"", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chatJson("지시", "질문", " ", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThat(hits.get()).as("서버까지 가지 않는다").isZero();
    }

    @Test
    void 빈_인자와_잘못된_timeout은_IllegalArgumentException() {
        GeminiLlmClient c = new GeminiLlmClient("http://127.0.0.1:1", KEY, MODEL, FIVE, 1);
        assertThatThrownBy(() -> c.chat(null, "질문", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chat(" ", "질문", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chat("지시", "", FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chat("지시", "질문", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chat("지시", "질문", Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chatJson("", "질문", SCHEMA, FIVE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.chatJson("지시", null, SCHEMA, FIVE)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 오류 응답 ----

    @Test
    void HTTP_오류는_모두_사용_불가이고_재시도_없이_한_번만_비밀이_새지_않는다() throws IOException {
        // 오류 본문에 오류 글자·키 전체·키 일부가 섞여 와도 예외·로그에 나오면 안 된다
        String errBody = "{\"error\":{\"code\":1,\"message\":\"" + ERROR_MESSAGE + " " + KEY + " " + KEY_PART + "\",\"status\":\"X\"}}";
        for (int status : new int[] {400, 401, 403, 404, 429, 500, 503}) {
            hits.set(0);
            logs.list.clear();
            startServer(status, errBody);
            GeminiLlmClient c = client();
            Throwable thrown = catchThrowable(() -> c.chat("지시", "질문", FIVE));
            assertThat(thrown).as("상태 %d", status).isInstanceOf(LlmUnavailableException.class);
            assertThat(hits.get()).as("상태 %d의 요청 수", status).isEqualTo(1);
            assertNoSecrets(thrown);
            assertThat(logText()).as("상태 숫자는 로그에 남는다").contains(String.valueOf(status));
            if (status == 429) assertThat(logText()).contains("한도 초과");
            // chatJson도 같다
            Throwable thrownJson = catchThrowable(() -> c.chatJson("지시", "질문", SCHEMA, FIVE));
            assertThat(thrownJson).isInstanceOf(LlmUnavailableException.class);
            assertNoSecrets(thrownJson);
            server.stop(0);
        }
    }

    @Test
    void 서버가_없으면_사용_불가() {
        GeminiLlmClient c = new GeminiLlmClient("http://127.0.0.1:1", KEY, MODEL, FIVE, 1);
        Throwable thrown = catchThrowable(() -> c.chat("지시", "질문", FIVE));
        assertThat(thrown).isInstanceOf(LlmUnavailableException.class);
        assertNoSecrets(thrown);
    }

    // ---- 200인데 쓸 수 없는 응답 ----

    @Test
    void 쓸_수_없는_200_응답은_모두_사용_불가() throws IOException {
        // 후보 글자를 ERROR_MESSAGE로 둔다: 쓸 수 없다고 판단한 응답의 글자가 예외·로그에 새면 assertNoSecrets가 잡는다
        String text = ERROR_MESSAGE;
        String[] bodies = {
            "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", // 차단
            "{\"promptFeedback\":{\"blockReason\":\"OTHER\"},\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]},\"finishReason\":\"STOP\"}]}",
            "{}", // candidates 없음
            "{\"candidates\":[]}", // 빈 배열
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]},\"finishReason\":\"SAFETY\"}]}",
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]},\"finishReason\":\"MAX_TOKENS\"}]}",
            "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"" + text + "\"}]}}]}", // finishReason 없음
            ok(""), // 빈 글자
            ok("  \n "), // 공백뿐
            "{\"candidates\":[{\"finishReason\":\"STOP\"}]}", // parts 없음
            "JSON이 아니다 " + text, // JSON 아님
            "", // 빈 본문
            "[1,2]" // 객체 아님
        };
        for (String body : bodies) {
            hits.set(0);
            logs.list.clear();
            startServer(200, body);
            GeminiLlmClient c = client();
            Throwable thrown = catchThrowable(() -> c.chat("지시", "질문", FIVE));
            assertThat(thrown).as("본문: %s", body).isInstanceOf(LlmUnavailableException.class);
            assertThat(hits.get()).isEqualTo(1);
            assertNoSecrets(thrown);
            server.stop(0);
        }
    }

    // ---- 한글·큰 응답·생각 part·연결 문제 ----

    // 한글 질문이 UTF-8 바이트로 가고, charset 표시 없는 응답의 한글 답도 안 깨지는지
    @Test
    void 한글_질문과_답이_UTF8로_깨지지_않고_오간다() throws IOException {
        String text = "자전거 3만원 이하 😀 가나다";
        startServer(200, ok(text));
        String answer = client().chat("한글 지시문", text, FIVE);
        assertThat(answer).isEqualTo(text);
        // 가짜 서버가 UTF-8로 읽은 본문에 한글이 그대로 있어야 한다
        assertThat(mapper.readTree(lastBody).path("contents").get(0).path("parts").get(0).path("text").asString()).isEqualTo(text);
        assertThat(mapper.readTree(lastBody).path("systemInstruction").path("parts").get(0).path("text").asString()).isEqualTo("한글 지시문");
    }

    // 수 MB 응답도 제한 시간 안에 정상 처리되는지
    @Test
    void 수_MB_응답도_빠르게_처리한다() throws IOException {
        String big = "가".repeat(2_000_000); // UTF-8로 약 6MB
        startServer(200, ok(big));
        long start = System.nanoTime();
        String answer = client().chat("지시", "질문", FIVE);
        assertThat(answer).hasSize(2_000_000);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(4000);
    }

    // thought:true part는 답에서 빼고, 생각 part만 있으면 빈 답으로 사용 불가
    @Test
    void 생각_part는_답에서_제외한다() throws IOException {
        startServer(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"생각중\"},{\"text\":\"답\"},{\"text\":\"변\"}]},\"finishReason\":\"STOP\"}]}");
        assertThat(client().chat("지시", "질문", FIVE)).isEqualTo("답변");
        server.stop(0);
        startServer(200, "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"생각만\"}]},\"finishReason\":\"STOP\"}]}");
        assertThatThrownBy(() -> client().chat("지시", "질문", FIVE)).isInstanceOf(LlmUnavailableException.class);
    }

    // 연결 거부는 제한 시간(10초)까지 기다리지 않고 바로 사용 불가. 느린 PC에서도 흔들리지 않게 8초까지 여유를 둔다.
    @Test
    void 연결_거부는_빠르게_사용_불가로_난다() {
        GeminiLlmClient c = new GeminiLlmClient("http://127.0.0.1:1", KEY, MODEL, Duration.ofSeconds(10), 1);
        long start = System.nanoTime();
        Throwable thrown = catchThrowable(() -> c.chat("지시", "질문", Duration.ofSeconds(10)));
        assertThat(thrown).isInstanceOf(LlmUnavailableException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(8000);
    }

    // 본문 도중 서버가 연결을 끊으면 멈추지 않고 사용 불가(또는 시간 초과) 예외
    @Test
    void 본문_도중_연결이_끊기면_예외로_끝난다() throws IOException {
        startServer(ex -> {
            ex.sendResponseHeaders(200, 100000); // 100000바이트 약속 후 일부만 보내고 끊는다
            ex.getResponseBody().write("{\"cand".getBytes(StandardCharsets.UTF_8));
            ex.getResponseBody().flush();
            ex.getResponseBody().close();
        });
        long start = System.nanoTime();
        Throwable thrown = catchThrowable(() -> client().chat("지시", "질문", FIVE));
        assertThat(thrown).isInstanceOfAny(LlmUnavailableException.class, LlmTimeoutException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5500);
        assertNoSecrets(thrown);
    }

    // ---- 시간 ----

    @Test
    void 본문이_천천히_와도_넘긴_제한_시간에서_시간_초과() throws Exception {
        startSlowBodyServer(ok("안녕"), 4000);
        GeminiLlmClient c = new GeminiLlmClient(url(), KEY, MODEL, Duration.ofSeconds(10), 2);
        long start = System.nanoTime();
        Throwable thrown = catchThrowable(() -> c.chat("지시", "질문", Duration.ofSeconds(1)));
        assertThat(thrown).isInstanceOf(LlmTimeoutException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
        assertThat(hits.get()).isEqualTo(1);
        assertNoSecrets(thrown);
        // 시간 초과로 끊긴 호출 스레드가 뒤늦게 "연결 또는 요청 오류" 로그를 덧붙이면 안 된다(이미 시간 초과로 처리됐다)
        Thread.sleep(500);
        assertThat(logText()).doesNotContain("연결 또는 요청 오류");
    }

    @Test
    void 넘긴_값이_생성자_timeout보다_길면_생성자_timeout에서_끊긴다() throws IOException {
        startSlowBodyServer(ok("안녕"), 4000);
        GeminiLlmClient c = new GeminiLlmClient(url(), KEY, MODEL, Duration.ofSeconds(1), 2);
        long start = System.nanoTime();
        Throwable thrown = catchThrowable(() -> c.chatJson("지시", "질문", SCHEMA, Duration.ofSeconds(30)));
        assertThat(thrown).isInstanceOf(LlmTimeoutException.class);
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(2500);
    }

    // ---- 스레드 상한 ----

    private static long aliveGeminiThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(t -> t.getName().equals("gemini-call") && t.isAlive()).count();
    }

    @Test
    void 호출_스레드가_상한까지_차면_기다리지_않고_사용_불가() throws Exception {
        startHangingServer();
        GeminiLlmClient c = new GeminiLlmClient(url(), KEY, MODEL, Duration.ofSeconds(10), 2);
        long before = aliveGeminiThreads();
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    c.chat("지시", "질문", Duration.ofSeconds(10));
                } catch (RuntimeException ignored) {
                    // 정리 중 끊겨도 상관없다
                }
            });
            t.start();
            callers.add(t);
        }
        long wait = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (hits.get() < 2 && System.nanoTime() < wait) Thread.sleep(20);
        assertThat(hits.get()).isEqualTo(2);

        long start = System.nanoTime();
        assertThatThrownBy(() -> c.chat("지시", "질문", Duration.ofSeconds(10))).isInstanceOf(LlmUnavailableException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(aliveGeminiThreads() - before).isLessThanOrEqualTo(2);
        assertThat(hits.get()).as("거절된 호출은 서버까지 가지 않는다").isEqualTo(2);

        release.countDown();
        for (Thread t : callers) t.join(10_000);
    }

    @Test
    void 시간_초과로_끝난_호출의_스레드는_풀려나_새_호출이_정상_답을_받는다() throws Exception {
        AtomicBoolean hangFirst = new AtomicBoolean(true);
        startServer(ex -> {
            if (hangFirst.get()) release.await(); // 래치가 열리기 전엔 스스로 풀어 주지 않는다
            reply(ex, 200, ok("안녕"));
        });
        GeminiLlmClient c = new GeminiLlmClient(url(), KEY, MODEL, Duration.ofSeconds(1), 1);
        // 상한 1개짜리 풀이 1초 뒤 시간 초과로 비워져야 다음 호출이 받아들여진다
        assertThatThrownBy(() -> c.chat("지시", "질문", Duration.ofSeconds(1))).isInstanceOf(LlmTimeoutException.class);
        assertThat(hits.get()).as("시간 초과 호출은 재시도 없이 한 번만").isEqualTo(1);
        hangFirst.set(false);
        String answer = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (answer == null && System.nanoTime() < deadline) {
            try {
                answer = c.chat("지시", "질문", Duration.ofSeconds(1));
            } catch (LlmUnavailableException busy) {
                Thread.sleep(50);
            }
        }
        assertThat(answer).isEqualTo("안녕");
    }
}
