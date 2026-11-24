package com.example.demo.market.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.example.demo.market.domain.ImageSafetyResult;
import com.example.demo.market.domain.SafeSearchLikelihood;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.domain.SafetyVerdict;
import com.example.demo.market.service.ImageSafetyJudge.Rule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S30-a: 구글 SafeSearch 검사기를 가짜 서버(JDK HttpServer)로 검사한다. 진짜 구글·스프링·DB 없이 돈다.
// 허가서 VisionPermitTextReaderTests와 같은 방식(가짜 서버 + 로그 캡처).
class VisionImageSafetyCheckerTests {

    private static final String KEY = "KEY-12345-SECRET";
    private static final String KEY_PART = KEY.substring(0, 8);
    private static final String SECRET_MSG = "SECRET-MSG";
    private static final byte[] IMAGE = "IMAGE-BYTES-0123456789-abcdefghijklmnop".getBytes(StandardCharsets.UTF_8);
    private static final String IMAGE_BASE64 = Base64.getEncoder().encodeToString(IMAGE);
    private static final Duration FIVE = Duration.ofSeconds(5);
    private static final long MAX_BYTES = 7L * 1024 * 1024;

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile String lastMethod;
    private volatile String lastPath;
    private volatile String lastQuery;
    private volatile String lastKeyHeader;
    private volatile String lastBody = "";
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService serverExecutor = Executors.newCachedThreadPool();
    private final List<VisionImageSafetyChecker> checkers = new ArrayList<>();

    private ListAppender<ILoggingEvent> logs;
    private Logger checkerLogger;

    @BeforeEach
    void captureLogs() {
        checkerLogger = (Logger) LoggerFactory.getLogger(VisionImageSafetyChecker.class);
        logs = new ListAppender<>();
        logs.start();
        checkerLogger.addAppender(logs);
    }

    @AfterEach
    void stop() {
        release.countDown();
        checkers.forEach(VisionImageSafetyChecker::close);
        checkerLogger.detachAppender(logs);
        if (server != null) server.stop(0);
        serverExecutor.shutdownNow();
    }

    // ---- 가짜 서버 ----

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private void startServer(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/", ex -> {
            hits.incrementAndGet();
            lastMethod = ex.getRequestMethod();
            lastPath = ex.getRequestURI().getPath();
            lastQuery = ex.getRequestURI().getRawQuery();
            lastKeyHeader = ex.getRequestHeaders().getFirst("x-goog-api-key");
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // 기본 기준(application.properties와 같은 값)
    private static ImageSafetyJudge 기본판정기() {
        Map<SafetyCategory, Rule> rules = new EnumMap<>(SafetyCategory.class);
        rules.put(SafetyCategory.ADULT, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.POSSIBLE));
        rules.put(SafetyCategory.VIOLENCE, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.LIKELY));
        rules.put(SafetyCategory.RACY, new Rule(null, SafeSearchLikelihood.VERY_LIKELY));
        return new ImageSafetyJudge(rules);
    }

    private VisionImageSafetyChecker checker() {
        return checker(FIVE, () -> true);
    }

    private VisionImageSafetyChecker checker(Duration timeout, BooleanSupplier limit) {
        VisionImageSafetyChecker c = new VisionImageSafetyChecker(url(), KEY, timeout, 4, MAX_BYTES, 기본판정기(), limit);
        checkers.add(c);
        return c;
    }

    // 구글이 주는 SafeSearch 응답 모양
    private static String 응답(String adult, String violence, String racy) {
        return "{\"responses\":[{\"safeSearchAnnotation\":{\"adult\":\"" + adult + "\",\"spoof\":\"UNLIKELY\",\"medical\":\"UNLIKELY\","
                + "\"violence\":\"" + violence + "\",\"racy\":\"" + racy + "\"}}]}";
    }

    private String logText() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : logs.list) {
            sb.append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) sb.append(e.getThrowableProxy().getMessage()).append('\n');
        }
        return sb.toString();
    }

    // 완료 조건 7: 로그에 키·사진 바이트·응답 본문이 없다
    private void assertNoSecretsInLogs() {
        assertThat(logs.list).allMatch(e -> e.getThrowableProxy() == null);
        assertThat(logText()).doesNotContain(KEY).doesNotContain(KEY_PART).doesNotContain(SECRET_MSG)
                .doesNotContain(IMAGE_BASE64.substring(0, 20)).doesNotContain(new String(IMAGE, StandardCharsets.UTF_8))
                .doesNotContain("x-goog-api-key");
    }

    // ---- 완료 조건 3·4: 등급 -> 판정 ----

    @Test
    void 성인물_VERY_LIKELY면_BLOCK_LIKELY면_FLAG_전부_VERY_UNLIKELY면_PASS() throws IOException {
        startServer(200, 응답("VERY_LIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        ImageSafetyResult block = checker().check(IMAGE);
        assertThat(block.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(block.category()).isEqualTo(SafetyCategory.ADULT);
        server.stop(0);

        startServer(200, 응답("LIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        assertThat(checker().check(IMAGE).verdict()).isEqualTo(SafetyVerdict.FLAG);
        server.stop(0);

        startServer(200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        assertThat(checker().check(IMAGE).verdict()).isEqualTo(SafetyVerdict.PASS);
        assertNoSecretsInLogs();
    }

    @Test
    void 선정성_VERY_LIKELY만이면_FLAG_LIKELY만이면_PASS() throws IOException {
        startServer(200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_LIKELY"));
        ImageSafetyResult flag = checker().check(IMAGE);
        assertThat(flag.verdict()).isEqualTo(SafetyVerdict.FLAG);
        assertThat(flag.category()).isEqualTo(SafetyCategory.RACY);
        server.stop(0);

        startServer(200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "LIKELY"));
        assertThat(checker().check(IMAGE).verdict()).isEqualTo(SafetyVerdict.PASS);
    }

    @Test
    void 여러_항목이_걸리면_가장_센_판정을_돌려준다() throws IOException {
        // 선정성 FLAG + 폭력 BLOCK -> BLOCK
        startServer(200, 응답("VERY_UNLIKELY", "VERY_LIKELY", "VERY_LIKELY"));
        ImageSafetyResult r = checker().check(IMAGE);
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(r.category()).isEqualTo(SafetyCategory.VIOLENCE);
    }

    // ---- 요청 모양 ----

    @Test
    void 요청은_POST_경로_x_goog_api_key_헤더_SAFE_SEARCH_DETECTION_본문이고_URL에_키가_없다() throws IOException {
        startServer(200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        checker().check(IMAGE);

        assertThat(lastMethod).isEqualTo("POST");
        assertThat(lastPath).isEqualTo("/v1/images:annotate");
        assertThat(lastQuery).as("키는 주소(쿼리)에 없어야 한다").isNull();
        assertThat(lastKeyHeader).isEqualTo(KEY);
        assertThat(lastBody).doesNotContain(KEY);
        JsonNode request = mapper.readTree(lastBody).path("requests").get(0);
        assertThat(request.path("image").path("content").asString()).isEqualTo(IMAGE_BASE64);
        assertThat(request.path("features").size()).isEqualTo(1);
        assertThat(request.path("features").get(0).path("type").asString()).isEqualTo("SAFE_SEARCH_DETECTION");
    }

    // ---- 완료 조건 5: 실패는 UNCHECKED, 예외 없음 ----

    @Test
    void 서버_500은_UNCHECKED이고_예외가_나지_않고_비밀이_새지_않는다() throws IOException {
        startServer(500, "{\"error\":{\"code\":500,\"message\":\"" + SECRET_MSG + " " + KEY + "\"}}");
        VisionImageSafetyChecker c = checker();
        assertThatCode(() -> assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.UNCHECKED)).doesNotThrowAnyException();
        assertThat(hits.get()).as("재시도 없이 한 번").isEqualTo(1);
        assertThat(logText()).contains("HTTP 500");
        assertNoSecretsInLogs();
    }

    @Test
    void 다른_HTTP_오류도_모두_UNCHECKED() throws IOException {
        for (int status : new int[] { 400, 401, 403, 429, 503 }) {
            startServer(status, "{\"error\":{\"message\":\"" + SECRET_MSG + "\"}}");
            assertThat(checker().check(IMAGE).verdict()).as("상태 %d", status).isEqualTo(SafetyVerdict.UNCHECKED);
            server.stop(0);
        }
        assertNoSecretsInLogs();
    }

    @Test
    void JSON이_아닌_200_응답은_UNCHECKED() throws IOException {
        startServer(200, "JSON이 아니다 " + SECRET_MSG);
        assertThat(checker().check(IMAGE).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertNoSecretsInLogs();
    }

    @Test
    void 쓸_수_없는_200_응답은_모두_UNCHECKED이고_PASS가_아니다() throws IOException {
        // 등급이 없는데 PASS로 치면 검사 못 한 사진이 통과한 것처럼 보이므로 UNCHECKED여야 한다
        String[] bodies = { "{}", "{\"responses\":[]}", "{\"responses\":[{}]}", "[1,2]", "",
                "{\"responses\":[{\"error\":{\"code\":7,\"message\":\"" + SECRET_MSG + "\"}}]}" };
        for (String body : bodies) {
            startServer(200, body);
            assertThat(checker().check(IMAGE).verdict()).as("본문: %s", body).isEqualTo(SafetyVerdict.UNCHECKED);
            server.stop(0);
        }
        assertThat(logText()).contains("code 7");
        assertNoSecretsInLogs();
    }

    @Test
    void 응답을_붙드는_서버는_시간_초과로_UNCHECKED() throws IOException {
        startServer(ex -> {
            release.await();
            reply(ex, 200, 응답("VERY_LIKELY", "VERY_LIKELY", "VERY_LIKELY"));
        });
        VisionImageSafetyChecker c = checker(Duration.ofSeconds(1), () -> true);
        long start = System.nanoTime();
        ImageSafetyResult r = c.check(IMAGE);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(elapsedMillis).isBetween(900L, 3000L);
        assertThat(logText()).contains("전체 상한 초과");
        assertNoSecretsInLogs();
    }

    @Test
    void 닫힌_포트_연결_거부는_UNCHECKED() {
        VisionImageSafetyChecker c = new VisionImageSafetyChecker("http://127.0.0.1:1", KEY, Duration.ofSeconds(10), 1, MAX_BYTES,
                기본판정기(), () -> true);
        checkers.add(c);
        assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertNoSecretsInLogs();
    }

    @Test
    void 빈_사진과_크기_상한을_넘는_사진은_구글을_부르지_않고_UNCHECKED() throws IOException {
        startServer(200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        VisionImageSafetyChecker c = new VisionImageSafetyChecker(url(), KEY, FIVE, 1, 10, 기본판정기(), () -> true);
        checkers.add(c);
        assertThat(c.check(new byte[0]).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(c.check(null).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(c.check(new byte[11]).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(hits.get()).isZero();
        assertThat(c.check(new byte[10]).verdict()).as("상한과 같은 크기는 통과해서 호출됨").isEqualTo(SafetyVerdict.PASS);
        assertThat(hits.get()).isEqualTo(1);
    }

    // ---- 완료 조건 6(검사기 쪽): 상한이 false면 구글을 안 부른다 ----

    @Test
    void 하루_상한_확인이_false면_구글을_부르지_않고_UNCHECKED() throws IOException {
        startServer(200, 응답("VERY_LIKELY", "VERY_LIKELY", "VERY_LIKELY"));
        VisionImageSafetyChecker c = checker(FIVE, () -> false);
        assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(hits.get()).isZero();
    }

    @Test
    void 하루_상한_확인이_예외를_던져도_UNCHECKED이고_구글을_부르지_않는다() throws IOException {
        startServer(200, 응답("VERY_LIKELY", "VERY_LIKELY", "VERY_LIKELY"));
        VisionImageSafetyChecker c = checker(FIVE, () -> {
            throw new IllegalStateException(SECRET_MSG);
        });
        assertThatCode(() -> assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.UNCHECKED)).doesNotThrowAnyException();
        assertThat(hits.get()).isZero();
        assertNoSecretsInLogs();
    }

    @Test
    void 호출마다_상한을_한_번씩_센다() throws IOException {
        startServer(200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        AtomicInteger counted = new AtomicInteger();
        VisionImageSafetyChecker c = checker(FIVE, () -> counted.incrementAndGet() <= 2);
        assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.PASS);
        assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.PASS);
        assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(hits.get()).isEqualTo(2);
    }

    // ---- 리뷰 반영(S30-a): 자리·반납·close·빠진 등급·리다이렉트·성공 로그 ----

    private VisionImageSafetyChecker checker(Duration timeout, int maxCallThreads, BooleanSupplier limit) {
        VisionImageSafetyChecker c = new VisionImageSafetyChecker(url(), KEY, timeout, maxCallThreads, MAX_BYTES, 기본판정기(), limit);
        checkers.add(c);
        return c;
    }

    // 리뷰 1: 자리가 가득 차면 하루 상한을 세지 않고 UNCHECKED
    @Test
    void 자리가_가득_차면_하루_상한을_세지_않고_UNCHECKED() throws Exception {
        startServer(ex -> {
            release.await();
            reply(ex, 200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        });
        AtomicInteger limitCalls = new AtomicInteger();
        VisionImageSafetyChecker c = checker(FIVE, 1, () -> {
            limitCalls.incrementAndGet();
            return true;
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<ImageSafetyResult> first = caller.submit(() -> c.check(IMAGE));
            // 첫 호출이 서버에 닿아 자리를 점유할 때까지 기다린다
            long deadline = System.nanoTime() + Duration.ofSeconds(4).toNanos();
            while (hits.get() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(hits.get()).isEqualTo(1);
            ImageSafetyResult second = c.check(IMAGE);
            assertThat(second.verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
            assertThat(limitCalls.get()).as("자리가 없으면 상한 supplier를 부르지 않는다").isEqualTo(1);
            assertThat(hits.get()).as("두 번째는 구글로 가지 않는다").isEqualTo(1);
            assertThat(logText()).contains("자리 가득 참");
            release.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS).verdict()).isEqualTo(SafetyVerdict.PASS);
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    // 리뷰 2: 시간 초과로 취소돼도 자리가 반납돼 다음 호출이 정상으로 구글을 부른다
    @Test
    void 시간_초과_뒤에도_자리가_반납돼_다음_호출이_정상이다() throws IOException {
        startServer(ex -> {
            if (hits.get() == 1) {
                release.await();
            }
            reply(ex, 200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        });
        AtomicInteger limitCalls = new AtomicInteger();
        VisionImageSafetyChecker c = checker(Duration.ofSeconds(1), 1, () -> {
            limitCalls.incrementAndGet();
            return true;
        });
        assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        ImageSafetyResult next = c.check(IMAGE);
        assertThat(next.verdict()).as("자리가 돌아와 정상 호출").isEqualTo(SafetyVerdict.PASS);
        assertThat(hits.get()).isEqualTo(2);
        assertThat(limitCalls.get()).isEqualTo(2);
    }

    // 리뷰 3: close() 뒤 check()는 예외 없이 UNCHECKED
    @Test
    void close_뒤_check는_예외_없이_UNCHECKED이고_구글을_부르지_않는다() throws IOException {
        startServer(200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        VisionImageSafetyChecker c = checker();
        c.close();
        assertThatCode(() -> assertThat(c.check(IMAGE).verdict()).isEqualTo(SafetyVerdict.UNCHECKED)).doesNotThrowAnyException();
        assertThat(hits.get()).isZero();
        assertNoSecretsInLogs();
    }

    // 리뷰 4: 등급이 비었거나 adult가 빠진 200 응답은 PASS가 아니라 UNCHECKED
    @Test
    void 빈_safeSearchAnnotation이나_adult가_빠진_응답은_UNCHECKED() throws IOException {
        String[] bodies = {
                "{\"responses\":[{\"safeSearchAnnotation\":{}}]}",
                "{\"responses\":[{\"safeSearchAnnotation\":{\"violence\":\"VERY_UNLIKELY\",\"racy\":\"VERY_UNLIKELY\"}}]}" };
        for (String body : bodies) {
            startServer(200, body);
            ImageSafetyResult r = checker().check(IMAGE);
            assertThat(r.verdict()).as("본문: %s", body).isEqualTo(SafetyVerdict.UNCHECKED);
            server.stop(0);
        }
        assertNoSecretsInLogs();
    }

    // 리뷰 6: 3xx는 따라가지 않고 UNCHECKED (키 헤더가 다른 곳으로 안 간다)
    @Test
    void 리다이렉트_응답은_따라가지_않고_UNCHECKED() throws Exception {
        AtomicInteger targetHits = new AtomicInteger();
        List<String> targetKeyHeaders = new ArrayList<>();
        HttpServer target = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        target.createContext("/", ex -> {
            targetHits.incrementAndGet();
            targetKeyHeaders.add(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            byte[] b = 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        target.start();
        try {
            String location = "http://127.0.0.1:" + target.getAddress().getPort() + "/stolen";
            for (int status : new int[] { 301, 302, 307, 308 }) {
                startServer(ex -> {
                    ex.getResponseHeaders().add("Location", location);
                    ex.sendResponseHeaders(status, -1);
                });
                assertThat(checker().check(IMAGE).verdict()).as("상태 %d", status).isEqualTo(SafetyVerdict.UNCHECKED);
                server.stop(0);
            }
            assertThat(targetHits.get()).as("리다이렉트 대상은 호출되지 않아야 한다").isZero();
            assertThat(targetKeyHeaders).isEmpty();
            assertNoSecretsInLogs();
        } finally {
            target.stop(0);
        }
    }

    // 리뷰 7: 성공 로그는 info, 판정과 항목·등급만 담고 비밀은 없다
    @Test
    void 성공_로그는_info로_PASS를_남기고_비밀은_없다() throws IOException {
        startServer(200, 응답("VERY_UNLIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        assertThat(checker().check(IMAGE).verdict()).isEqualTo(SafetyVerdict.PASS);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                && e.getFormattedMessage().contains("사진 검사: PASS"));
        assertThat(logText()).doesNotContain("safeSearchAnnotation").doesNotContain("spoof").doesNotContain("medical");
        assertNoSecretsInLogs();
    }

    @Test
    void 성공_로그는_FLAG와_BLOCK을_항목_등급과_함께_남기고_비밀은_없다() throws IOException {
        startServer(200, 응답("LIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        assertThat(checker().check(IMAGE).verdict()).isEqualTo(SafetyVerdict.FLAG);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                && e.getFormattedMessage().contains("사진 검사: FLAG ADULT LIKELY"));
        server.stop(0);

        startServer(200, 응답("VERY_LIKELY", "VERY_UNLIKELY", "VERY_UNLIKELY"));
        assertThat(checker().check(IMAGE).verdict()).isEqualTo(SafetyVerdict.BLOCK);
        assertThat(logs.list).anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.INFO
                && e.getFormattedMessage().contains("사진 검사: BLOCK ADULT VERY_LIKELY"));
        assertThat(logText()).doesNotContain("safeSearchAnnotation").doesNotContain("spoof");
        assertNoSecretsInLogs();
    }

    // ---- 생성자·toString ----

    @Test
    void toString에는_키가_없다() {
        VisionImageSafetyChecker c = new VisionImageSafetyChecker("http://127.0.0.1:1", KEY, FIVE, 2, MAX_BYTES, 기본판정기(), () -> true);
        checkers.add(c);
        assertThat(c.toString()).doesNotContain(KEY).doesNotContain(KEY_PART);
    }
}
