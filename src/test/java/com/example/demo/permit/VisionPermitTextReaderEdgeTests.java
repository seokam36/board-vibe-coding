package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.demo.permit.service.VisionPermitTextReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.ObjectMapper;

// Vision 판독기의 경계 상황(큰 응답, 인코딩, 헤더, 늦은 응답, 닫힘, 동시, 리다이렉트)을 가짜 서버로 검사한다.
class VisionPermitTextReaderEdgeTests {

    private static final String KEY = "KEY-12345-SECRET";
    private static final byte[] IMAGE = "IMAGE-BYTES-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final Duration FIVE = Duration.ofSeconds(5);
    private static final long MAX_BYTES = 7L * 1024 * 1024;

    private final ExecutorService serverExecutor = Executors.newCachedThreadPool();
    private final AtomicInteger hits = new AtomicInteger();
    private HttpServer server;
    private HttpServer other;
    private final AtomicInteger otherHits = new AtomicInteger();
    private volatile String otherKeyHeader;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        if (other != null) other.stop(0);
        serverExecutor.shutdownNow();
    }

    private interface Handler {
        void handle(HttpExchange ex) throws Exception;
    }

    private HttpServer start(AtomicInteger counter, Handler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.setExecutor(serverExecutor);
        s.createContext("/", ex -> {
            counter.incrementAndGet();
            try {
                ex.getRequestBody().readAllBytes();
                handler.handle(ex);
            } catch (Exception ignored) {
                // 클라이언트가 먼저 끊은 경우
            } finally {
                ex.close();
            }
        });
        s.start();
        return s;
    }

    private void startServer(Handler handler) throws IOException {
        server = start(hits, handler);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private VisionPermitTextReader reader(Duration timeout, int threads) {
        return new VisionPermitTextReader(url(), KEY, timeout, threads, MAX_BYTES);
    }

    private static String okBody(String text) {
        return "{\"responses\":[{\"fullTextAnnotation\":{\"text\":" + new ObjectMapper().writeValueAsString(text) + "}}]}";
    }

    private static void reply(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (contentType != null) ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    @Test
    void 수_MB_큰_응답_글자도_그대로_돌려준다() throws IOException {
        String big = "가나다abc\n".repeat(600_000); // 약 6MB(UTF-8)
        startServer(ex -> reply(ex, 200, "application/json", okBody(big)));
        Optional<String> result = reader(Duration.ofSeconds(20), 1).read(IMAGE, "image/jpeg");
        assertThat(result).isPresent();
        assertThat(result.get().length()).isEqualTo(big.length());
        assertThat(result.get().equals(big)).isTrue();
    }

    @Test
    void 한글_이모지_CRLF가_섞인_글자도_UTF8로_그대로_온다() throws IOException {
        String text = "성명: 홍길동\r\nNAME: Hong 😀\r\n국적: 대한민국 🇰🇷\n끝";
        // charset 없는 Content-Type이라도 UTF-8로 읽어야 한다
        startServer(ex -> reply(ex, 200, "application/json", okBody(text)));
        assertThat(reader(FIVE, 1).read(IMAGE, "image/jpeg")).isEqualTo(Optional.of(text));
    }

    @Test
    void fullTextAnnotation_없이_textAnnotations만_있으면_현재는_empty() throws IOException {
        startServer(ex -> reply(ex, 200, "application/json",
                "{\"responses\":[{\"textAnnotations\":[{\"description\":\"ONLY-DESC\"}]}]}"));
        assertThat(reader(FIVE, 1).read(IMAGE, "image/jpeg")).isEmpty();
    }

    @Test
    void 응답_Content_Type이_없어도_JSON_글자를_읽는다() throws IOException {
        startServer(ex -> reply(ex, 200, null, okBody("무헤더")));
        assertThat(reader(FIVE, 1).read(IMAGE, "image/jpeg")).isEqualTo(Optional.of("무헤더"));
    }

    @Test
    void 응답_Content_Type이_text_plain이어도_JSON_글자를_읽는다() throws IOException {
        startServer(ex -> reply(ex, 200, "text/plain", okBody("평문헤더")));
        assertThat(reader(FIVE, 1).read(IMAGE, "image/jpeg")).isEqualTo(Optional.of("평문헤더"));
    }

    @Test
    void 시간_초과_뒤_늦게_온_응답이_다음_호출에_섞이지_않는다() throws IOException {
        AtomicInteger order = new AtomicInteger();
        startServer(ex -> {
            if (order.incrementAndGet() == 1) {
                Thread.sleep(2000); // 첫 호출만 늦게 OLD 응답
                reply(ex, 200, "application/json", okBody("OLD"));
            } else {
                reply(ex, 200, "application/json", okBody("NEW"));
            }
        });
        VisionPermitTextReader r = reader(Duration.ofSeconds(1), 2);
        assertThat(r.read(IMAGE, "image/jpeg")).isEmpty();
        assertThat(r.read(IMAGE, "image/jpeg")).isEqualTo(Optional.of("NEW"));
        // 늦은 응답이 도착할 시간을 준 뒤 다시 불러도 새 응답만 온다
        try {
            Thread.sleep(2500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertThat(r.read(IMAGE, "image/jpeg")).isEqualTo(Optional.of("NEW"));
    }

    @Test
    void close_뒤_read는_예외_없이_empty() throws IOException {
        startServer(ex -> reply(ex, 200, "application/json", okBody("A")));
        VisionPermitTextReader r = reader(FIVE, 1);
        assertThat(r.read(IMAGE, "image/jpeg")).contains("A");
        r.close();
        assertThat(r.read(IMAGE, "image/jpeg")).isEmpty();
        r.close(); // 두 번 닫아도 예외 없음
    }

    @Test
    void 동시_상한_2에서_두_호출이_모두_성공한다() throws Exception {
        CountDownLatch bothArrived = new CountDownLatch(2);
        startServer(ex -> {
            bothArrived.countDown();
            // 둘이 동시에 도착해야만 응답한다(하나씩 처리되면 시간 초과로 실패)
            if (!bothArrived.await(5, TimeUnit.SECONDS)) {
                reply(ex, 500, "application/json", "{}");
                return;
            }
            reply(ex, 200, "application/json", okBody("OK"));
        });
        VisionPermitTextReader r = reader(Duration.ofSeconds(8), 2);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<String>> a = callers.submit(() -> r.read(IMAGE, "image/jpeg"));
            Future<Optional<String>> b = callers.submit(() -> r.read(IMAGE, "image/jpeg"));
            assertThat(a.get(10, TimeUnit.SECONDS)).isEqualTo(Optional.of("OK"));
            assertThat(b.get(10, TimeUnit.SECONDS)).isEqualTo(Optional.of("OK"));
            assertThat(hits.get()).isEqualTo(2);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void 리다이렉트는_따라가지_않고_다른_호스트로_키가_가지_않는다() throws IOException {
        other = start(otherHits, ex -> {
            otherKeyHeader = ex.getRequestHeaders().getFirst("x-goog-api-key");
            reply(ex, 200, "application/json", okBody("REDIRECTED"));
        });
        String target = "http://127.0.0.1:" + other.getAddress().getPort() + "/v1/images:annotate";
        for (int status : new int[] {301, 302, 307, 308}) {
            hits.set(0);
            if (server != null) server.stop(0);
            startServer(ex -> {
                ex.getResponseHeaders().add("Location", target);
                ex.sendResponseHeaders(status, -1);
            });
            assertThat(reader(FIVE, 1).read(IMAGE, "image/jpeg")).as("상태 %d", status).isEmpty();
            assertThat(hits.get()).as("상태 %d 원래 서버 호출", status).isEqualTo(1);
        }
        assertThat(otherHits.get()).as("리다이렉트 대상 서버는 호출되면 안 된다").isZero();
        assertThat(otherKeyHeader).as("다른 호스트로 키가 가면 안 된다").isNull();
    }
}
