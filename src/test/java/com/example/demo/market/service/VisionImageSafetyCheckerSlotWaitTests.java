package com.example.demo.market.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.example.demo.market.domain.ImageSafetyResult;
import com.example.demo.market.domain.SafeSearchLikelihood;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.domain.SafetyVerdict;
import com.example.demo.market.service.ImageSafetyJudge.Rule;
import com.sun.net.httpserver.HttpServer;

// S30-b 리뷰 반영: 동시 검사 자리(maxCallThreads=1)가 찼을 때 slotWait만큼 기다리는 동작을 가짜 서버로 검사한다.
// 첫 호출이 자리를 쥐고 있는 시간은 가짜 서버의 응답 지연(holdMillis)으로 정한다. 진짜 구글은 부르지 않는다.
class VisionImageSafetyCheckerSlotWaitTests {

    private static final byte[] IMAGE = "IMAGE-BYTES-0123456789".getBytes(StandardCharsets.UTF_8);
    private static final String OK_BODY = "{\"responses\":[{\"safeSearchAnnotation\":{\"adult\":\"UNLIKELY\","
            + "\"violence\":\"UNLIKELY\",\"racy\":\"UNLIKELY\"}}]}";

    private HttpServer server;
    private VisionImageSafetyChecker checker;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final AtomicInteger hits = new AtomicInteger();
    private final CountDownLatch firstHit = new CountDownLatch(1);
    private final AtomicInteger limitCalls = new AtomicInteger();
    private volatile long holdMillis;

    @AfterEach
    void stop() {
        if (checker != null) checker.close();
        if (server != null) server.stop(0);
        pool.shutdownNow();
    }

    private static ImageSafetyJudge 판정기() {
        Map<SafetyCategory, Rule> rules = new EnumMap<>(SafetyCategory.class);
        rules.put(SafetyCategory.ADULT, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.POSSIBLE));
        rules.put(SafetyCategory.VIOLENCE, new Rule(SafeSearchLikelihood.VERY_LIKELY, SafeSearchLikelihood.LIKELY));
        rules.put(SafetyCategory.RACY, new Rule(null, SafeSearchLikelihood.VERY_LIKELY));
        return new ImageSafetyJudge(rules);
    }

    // 첫 번째로 온 요청만 holdMillis 동안 붙들고, 그 뒤 요청은 바로 답한다
    private void 서버시작() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(pool);
        server.createContext("/", ex -> {
            int n = hits.incrementAndGet();
            try {
                ex.getRequestBody().readAllBytes();
                if (n == 1) {
                    firstHit.countDown();
                    Thread.sleep(holdMillis);
                }
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
    }

    private void 검사기만들기(Duration slotWait) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        checker = new VisionImageSafetyChecker(url, "KEY-12345", Duration.ofSeconds(10), 1, slotWait, 7L * 1024 * 1024,
                판정기(), () -> {
                    limitCalls.incrementAndGet();
                    return true;
                });
    }

    // 첫 호출을 백그라운드로 시작해 자리를 쥐게 한다(서버가 첫 요청을 받을 때까지 기다린다)
    private CompletableFuture<ImageSafetyResult> 첫호출로_자리_잡기() throws Exception {
        CompletableFuture<ImageSafetyResult> first = CompletableFuture.supplyAsync(() -> checker.check(IMAGE), pool);
        assertThat(firstHit.await(5, TimeUnit.SECONDS)).as("첫 호출이 서버에 닿아야 한다").isTrue();
        return first;
    }

    // (a) 자리가 곧 나면 기다렸다가 정상 호출한다
    @Test
    void 자리가_slotWait_안에_나면_기다렸다가_정상_검사한다() throws Exception {
        holdMillis = 300;
        서버시작();
        검사기만들기(Duration.ofSeconds(2));
        CompletableFuture<ImageSafetyResult> first = 첫호출로_자리_잡기();

        long start = System.nanoTime();
        ImageSafetyResult second = checker.check(IMAGE);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(second.verdict()).isEqualTo(SafetyVerdict.PASS);
        assertThat(elapsedMs).as("자리가 날 때까지 실제로 기다렸다").isGreaterThanOrEqualTo(100);
        assertThat(first.get(5, TimeUnit.SECONDS).verdict()).isEqualTo(SafetyVerdict.PASS);
        assertThat(hits.get()).isEqualTo(2);
        assertThat(limitCalls.get()).isEqualTo(2);
    }

    // (b) slotWait보다 오래 붙들면 UNCHECKED이고 하루 상한(구글 호출권)은 건드리지 않는다
    @Test
    void 자리가_slotWait_안에_안_나면_UNCHECKED이고_하루_상한은_건드리지_않는다() throws Exception {
        holdMillis = 3000;
        서버시작();
        검사기만들기(Duration.ofMillis(500));
        CompletableFuture<ImageSafetyResult> first = 첫호출로_자리_잡기();
        assertThat(limitCalls.get()).as("첫 호출이 상한을 1번 센 상태").isEqualTo(1);

        long start = System.nanoTime();
        ImageSafetyResult second = checker.check(IMAGE);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(second.verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(elapsedMs).as("slotWait(500ms)만큼은 기다렸다").isGreaterThanOrEqualTo(400);
        assertThat(elapsedMs).as("그 이상 오래 기다리지 않았다").isLessThan(2500);
        assertThat(limitCalls.get()).as("못 잡았으면 하루 상한을 안 센다").isEqualTo(1);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(first.get(10, TimeUnit.SECONDS).verdict()).isEqualTo(SafetyVerdict.PASS);
    }

    // (c) 기다리는 중 인터럽트: UNCHECKED, 인터럽트 플래그는 유지, 상한 안 건드림
    @Test
    void 자리를_기다리다_인터럽트되면_UNCHECKED이고_인터럽트_플래그가_남는다() throws Exception {
        holdMillis = 3000;
        서버시작();
        검사기만들기(Duration.ofSeconds(5));
        첫호출로_자리_잡기();

        AtomicBoolean flagAfter = new AtomicBoolean();
        CompletableFuture<ImageSafetyResult> waiter = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            ImageSafetyResult r = checker.check(IMAGE);
            flagAfter.set(Thread.currentThread().isInterrupted());
            waiter.complete(r);
        });
        t.start();
        Thread.sleep(300); // 자리를 기다리는 중이 되게 한다
        long start = System.nanoTime();
        t.interrupt();

        ImageSafetyResult r = waiter.get(3, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        assertThat(r.verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(flagAfter.get()).as("인터럽트 플래그가 되살려져 있어야 한다").isTrue();
        assertThat(elapsedMs).as("인터럽트 즉시 빠져나왔다(5초를 다 기다리지 않음)").isLessThan(2000);
        assertThat(limitCalls.get()).isEqualTo(1);
        assertThat(hits.get()).isEqualTo(1);
    }

    // slotWait 0은 기존처럼 기다리지 않고 즉시 UNCHECKED
    @Test
    void slotWait_0이면_자리가_없을_때_기다리지_않고_바로_UNCHECKED이다() throws Exception {
        holdMillis = 2000;
        서버시작();
        검사기만들기(Duration.ZERO);
        CompletableFuture<ImageSafetyResult> first = 첫호출로_자리_잡기();

        long start = System.nanoTime();
        ImageSafetyResult second = checker.check(IMAGE);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(second.verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(elapsedMs).isLessThan(300);
        assertThat(limitCalls.get()).isEqualTo(1);
        first.get(10, TimeUnit.SECONDS);
    }

    // 기존 7인자 생성자는 slotWait 0과 같다
    @Test
    void 기존_7인자_생성자는_자리_대기_없이_즉시_UNCHECKED이다() throws Exception {
        holdMillis = 2000;
        서버시작();
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        checker = new VisionImageSafetyChecker(url, "KEY-12345", Duration.ofSeconds(10), 1, 7L * 1024 * 1024, 판정기(), () -> {
            limitCalls.incrementAndGet();
            return true;
        });
        CompletableFuture<ImageSafetyResult> first = 첫호출로_자리_잡기();

        long start = System.nanoTime();
        ImageSafetyResult second = checker.check(IMAGE);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertThat(second.verdict()).isEqualTo(SafetyVerdict.UNCHECKED);
        assertThat(elapsedMs).isLessThan(300);
        first.get(10, TimeUnit.SECONDS);
    }
}
