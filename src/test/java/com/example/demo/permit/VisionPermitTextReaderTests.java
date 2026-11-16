package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.example.demo.permit.service.VisionPermitTextReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 구글 Vision 판독기를 가짜 서버(JDK HttpServer)로 검사한다. 실제 구글·스프링·DB 없이 돈다.
class VisionPermitTextReaderTests {

    private static final String KEY = "KEY-12345-SECRET";
    private static final String KEY_PART = KEY.substring(0, 8); // 키 일부만 새도 안 된다
    private static final String SECRET_MSG = "SECRET-MSG";
    private static final String READ_TEXT = "READ-TEXT-LEAK-CHECK";
    private static final byte[] IMAGE = "IMAGE-BYTES-0123456789-abcdefghijklmnop".getBytes(StandardCharsets.UTF_8);
    private static final String IMAGE_BASE64 = Base64.getEncoder().encodeToString(IMAGE);
    private static final Duration FIVE = Duration.ofSeconds(5);
    private static final long MAX_BYTES = 7L * 1024 * 1024;

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
    private final List<VisionPermitTextReader> readers = new ArrayList<>();
    private volatile String lastFieldMask;

    private ListAppender<ILoggingEvent> logs;
    private Logger readerLogger;

    @BeforeEach
    void captureLogs() {
        readerLogger = (Logger) LoggerFactory.getLogger(VisionPermitTextReader.class);
        logs = new ListAppender<>();
        logs.start();
        readerLogger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        release.countDown();
        readers.forEach(VisionPermitTextReader::close);
        readerLogger.detachAppender(logs);
        if (server != null) server.stop(0);
        serverExecutor.shutdownNow();
    }

    // ---- 가짜 서버 ----

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    // 모든 가짜 서버의 공통 틀: 받은 요청을 기록하고, 응답은 handler에게 맡긴다
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
            lastFieldMask = ex.getRequestHeaders().getFirst("X-Goog-FieldMask");
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

    private void startServer(int status, String body) throws IOException {
        startServer(ex -> reply(ex, status, body));
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    // 래치가 열릴 때까지 응답을 안 주고 붙드는 서버
    private void startHangingServer() throws IOException {
        startServer(ex -> {
            release.await();
            reply(ex, 200, okBody("x"));
        });
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private VisionPermitTextReader reader() {
        return track(new VisionPermitTextReader(url(), KEY, FIVE, 4, MAX_BYTES));
    }

    // 만든 판독기를 기억해 두었다가 테스트가 끝나면 닫는다(스레드·연결 정리)
    private VisionPermitTextReader track(VisionPermitTextReader r) {
        readers.add(r);
        return r;
    }

    private static String okBody(String text) {
        return "{\"responses\":[{\"fullTextAnnotation\":{\"text\":" + new ObjectMapper().writeValueAsString(text) + "}}]}";
    }

    private String logText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logs.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) sb.append(e.getThrowableProxy().getMessage()).append('\n');
        }
        return sb.toString();
    }

    // 로그에 비밀·개인정보가 없는지 본다
    private void assertNoSecretsInLogs() {
        // 예외 객체를 로그에 넘기지 않는다(스택 트레이스에 주소·메시지가 딸려 나오지 않게)
        assertThat(logs.list).allMatch(e -> e.getThrowableProxy() == null);
        assertThat(logText()).doesNotContain(KEY).doesNotContain(KEY_PART).doesNotContain(SECRET_MSG)
                .doesNotContain(READ_TEXT).doesNotContain(IMAGE_BASE64.substring(0, 20)).doesNotContain("x-goog-api-key");
    }

    // ---- 성공과 요청 모양 ----

    @Test
    void 성공_응답의_글자를_줄바꿈_그대로_돌려준다() throws IOException {
        // 읽은 글자(READ_TEXT)가 돌려주는 값에는 있고 로그에는 없어야 한다
        startServer(200, okBody(READ_TEXT + "\nB"));
        assertThat(reader().read(IMAGE, "image/jpeg")).isEqualTo(Optional.of(READ_TEXT + "\nB"));
        assertNoSecretsInLogs();
    }

    @Test
    void 요청은_POST_경로_키_헤더_본문_모양이_맞다() throws IOException {
        startServer(200, okBody("A\nB"));
        reader().read(IMAGE, "image/jpeg");

        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/v1/images:annotate");
        assertThat(lastQuery).as("키는 주소(쿼리)에 없어야 한다").isNull();
        assertThat(lastKeyHeader).isEqualTo(KEY);
        assertThat(lastContentType).startsWith("application/json");
        assertThat(lastBody).doesNotContain(KEY);

        JsonNode request = mapper.readTree(lastBody).path("requests").get(0);
        assertThat(request.path("image").path("content").asString()).isEqualTo(IMAGE_BASE64);
        assertThat(request.path("features").get(0).path("type").asString()).isEqualTo("DOCUMENT_TEXT_DETECTION");
        JsonNode hints = request.path("imageContext").path("languageHints");
        assertThat(hints.size()).isEqualTo(2);
        assertThat(hints.get(0).asString()).isEqualTo("ko");
        assertThat(hints.get(1).asString()).isEqualTo("en");
    }

    @Test
    void baseUrl_끝_슬래시는_무시한다() throws IOException {
        startServer(200, okBody("응"));
        VisionPermitTextReader r = track(new VisionPermitTextReader(url() + "/", KEY, FIVE, 1, MAX_BYTES));
        assertThat(r.read(IMAGE, "image/png")).contains("응");
        assertThat(lastPath).isEqualTo("/v1/images:annotate");
    }

    // ---- 글자가 없거나 쓸 수 없는 200 응답 ----

    @Test
    void 글자_없음_공백뿐_빈_배열_JSON_아님은_모두_empty() throws IOException {
        String[] bodies = {
            "{\"responses\":[{}]}",
            "{\"responses\":[{\"fullTextAnnotation\":{}}]}",
            "{\"responses\":[{\"fullTextAnnotation\":{\"text\":null}}]}",
            okBody("  \n "),
            okBody(""),
            "{\"responses\":[]}",
            "{}",
            "JSON이 아니다 " + READ_TEXT,
            "",
            "[1,2]"
        };
        for (String body : bodies) {
            hits.set(0);
            startServer(200, body);
            VisionPermitTextReader r = reader();
            assertThat(r.read(IMAGE, "image/jpeg")).as("본문: %s", body).isEmpty();
            assertThat(hits.get()).isEqualTo(1);
            server.stop(0);
        }
        assertNoSecretsInLogs();
    }

    // ---- 오류 ----

    @Test
    void HTTP_오류는_모두_empty이고_재시도_없이_한_번만_비밀이_새지_않는다() throws IOException {
        String errBody = "{\"error\":{\"code\":1,\"message\":\"" + SECRET_MSG + " " + KEY + " " + KEY_PART + "\"}}";
        for (int status : new int[] {400, 401, 403, 429, 500, 503}) {
            hits.set(0);
            logs.list.clear();
            startServer(status, errBody);
            VisionPermitTextReader r = reader();
            assertThat(r.read(IMAGE, "image/jpeg")).as("상태 %d", status).isEmpty();
            assertThat(hits.get()).as("상태 %d의 요청 수", status).isEqualTo(1);
            assertThat(logText()).as("상태 숫자는 로그에 남는다").contains("HTTP " + status);
            assertNoSecretsInLogs();
            server.stop(0);
        }
    }

    @Test
    void 사진_단위_error는_empty이고_코드_숫자만_로그에_남는다() throws IOException {
        startServer(200, "{\"responses\":[{\"error\":{\"code\":7,\"message\":\"" + SECRET_MSG + "\"}}]}");
        assertThat(reader().read(IMAGE, "image/jpeg")).isEmpty();
        assertThat(hits.get()).isEqualTo(1);
        assertThat(logText()).contains("code 7");
        assertNoSecretsInLogs();
    }

    @Test
    void 닫힌_포트_연결_거부는_empty() {
        VisionPermitTextReader r = track(new VisionPermitTextReader("http://127.0.0.1:1", KEY, Duration.ofSeconds(10), 1, MAX_BYTES));
        long start = System.nanoTime();
        assertThat(r.read(IMAGE, "image/jpeg")).isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(8000);
        assertNoSecretsInLogs();
    }

    // ---- 시간 ----

    @Test
    void 응답을_붙드는_서버는_전체_상한에서_empty() throws IOException {
        startHangingServer();
        VisionPermitTextReader r = track(new VisionPermitTextReader(url(), KEY, Duration.ofSeconds(1), 2, MAX_BYTES));
        long start = System.nanoTime();
        assertThat(r.read(IMAGE, "image/jpeg")).isEmpty();
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(elapsedMillis).isBetween(900L, 3000L);
        assertThat(hits.get()).as("시간 초과도 재시도 없이 한 번").isEqualTo(1);
        assertThat(logText()).contains("전체 상한 초과");
        assertNoSecretsInLogs();
    }

    // ---- 동시 호출 상한 ----

    @Test
    void 동시_상한이_차면_기다리지_않고_empty이고_서버까지_가지_않는다() throws Exception {
        startHangingServer();
        VisionPermitTextReader r = track(new VisionPermitTextReader(url(), KEY, Duration.ofSeconds(10), 1, MAX_BYTES));
        Thread first = new Thread(() -> r.read(IMAGE, "image/jpeg"));
        first.start();
        long wait = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (hits.get() < 1 && System.nanoTime() < wait) Thread.sleep(20);
        assertThat(hits.get()).isEqualTo(1);

        long start = System.nanoTime();
        assertThat(r.read(IMAGE, "image/jpeg")).isEmpty();
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
        assertThat(hits.get()).as("거절된 호출은 서버까지 가지 않는다").isEqualTo(1);
        assertNoSecretsInLogs();

        release.countDown();
        first.join(10_000);
    }

    // 시간 초과로 끝난 호출의 자리가 풀려서, 같은 판독기로 다시 부르면 정상 응답을 받는다
    @Test
    void 시간_초과_뒤_자리가_풀려_같은_판독기로_다시_읽을_수_있다() throws IOException {
        startHangingServer();
        VisionPermitTextReader r = track(new VisionPermitTextReader(url(), KEY, Duration.ofSeconds(1), 1, MAX_BYTES));
        assertThat(r.read(IMAGE, "image/jpeg")).isEmpty();
        release.countDown(); // 이제부터 서버는 바로 답한다(첫 요청도 풀린다)
        long start = System.nanoTime();
        Optional<String> result = Optional.empty();
        while (result.isEmpty() && System.nanoTime() - start < Duration.ofSeconds(3).toNanos()) {
            result = r.read(IMAGE, "image/jpeg");
        }
        assertThat(result).contains("x");
    }

    @Test
    void 요청에_FieldMask_헤더가_있다() throws IOException {
        startServer(200, okBody("A"));
        reader().read(IMAGE, "image/jpeg");
        assertThat(lastFieldMask).isEqualTo("responses.fullTextAnnotation.text,responses.error.code");
        assertThat(lastQuery).isNull();
    }

    @Test
    void 닫은_뒤_읽으면_empty이고_로그가_닫힘이다() throws IOException {
        startServer(200, okBody("A"));
        VisionPermitTextReader r = reader();
        r.close();
        assertThat(r.read(IMAGE, "image/jpeg")).isEmpty();
        assertThat(hits.get()).isZero();
        assertThat(logText()).contains("닫혀 있음").doesNotContain("풀 가득 참");
    }

    // ---- 크기 상한 ----

    @Test
    void 크기_상한을_넘으면_호출하지_않고_empty() throws IOException {
        startServer(200, okBody("A"));
        VisionPermitTextReader r = track(new VisionPermitTextReader(url(), KEY, FIVE, 1, 10));
        assertThat(r.read(new byte[11], "image/jpeg")).isEmpty();
        assertThat(hits.get()).isZero();
        // 상한과 같은 크기는 통과
        assertThat(r.read(new byte[10], "image/jpeg")).contains("A");
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void 빈_사진은_호출하지_않고_empty() throws IOException {
        startServer(200, okBody("A"));
        assertThat(reader().read(new byte[0], "image/jpeg")).isEmpty();
        assertThat(reader().read(null, "image/jpeg")).isEmpty();
        assertThat(hits.get()).isZero();
    }

    // ---- 생성자·toString ----

    @Test
    void 잘못된_생성자_인자는_IllegalArgumentException_메시지에_키_없음() {
        String u = "http://127.0.0.1:1";
        assertThatThrownBy(() -> new VisionPermitTextReader(" ", KEY, FIVE, 1, MAX_BYTES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisionPermitTextReader(u, " ", FIVE, 1, MAX_BYTES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisionPermitTextReader(u, KEY + "\n", FIVE, 1, MAX_BYTES))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> new VisionPermitTextReader(u + "?a=1", KEY, FIVE, 1, MAX_BYTES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisionPermitTextReader(u, KEY, Duration.ZERO, 1, MAX_BYTES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisionPermitTextReader(u, KEY, FIVE, 0, MAX_BYTES)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new VisionPermitTextReader(u, KEY, FIVE, 1, 0)).isInstanceOf(IllegalArgumentException.class);
        // scheme이나 host가 없는 주소
        assertThatThrownBy(() -> new VisionPermitTextReader("example.com", KEY, FIVE, 1, MAX_BYTES))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("example.com");
        assertThatThrownBy(() -> new VisionPermitTextReader("http:", KEY, FIVE, 1, MAX_BYTES)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toString에는_키가_없다() {
        VisionPermitTextReader r = new VisionPermitTextReader("http://127.0.0.1:1", KEY, FIVE, 2, MAX_BYTES);
        assertThat(r.toString()).doesNotContain(KEY).doesNotContain(KEY_PART);
    }
}
