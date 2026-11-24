package com.example.demo.market.service;

import com.example.demo.market.domain.ImageSafetyResult;
import com.example.demo.market.domain.SafeSearchLikelihood;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.domain.SafetyVerdict;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 구글 Cloud Vision SafeSearch(images:annotate)로 중고 글 사진을 검사한다 (S30-a).
// VisionPermitTextReader와 같은 방식이다: 호출을 별도 스레드에서 돌리고 "시간 초과" 판정은 Future.get 한 곳에서만 한다.
// (허가서 코드를 건드리지 않으려고 공용으로 빼지 않고 같은 모양으로 따로 만들었다.)
// 보안·개인정보 원칙: API 키, 사진 바이트, base64, 응답 본문, 오류 message는 로그·예외에 절대 넣지 않는다.
// 로그에는 실패 종류, HTTP 상태 숫자, 예외 클래스 이름, error.code 숫자만 남긴다.
// 검사 못 한 모든 경우는 예외 대신 UNCHECKED로 돌려준다. 빈 등록은 MarketSafeSearchConfig가 한다.
public class VisionImageSafetyChecker implements ImageSafetyChecker, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VisionImageSafetyChecker.class);

    // 연결 제한: 서버에 닿지 않을 때 오래 기다리지 않게 전체 상한과 따로 짧게 둔다
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // 읽기 제한은 전체 상한보다 일부러 길게: "시간 초과" 판정을 Future 한 곳에서만 하려는 것
    private static final Duration READ_TIMEOUT_MARGIN = Duration.ofSeconds(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final RestClient restClient;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final int maxCallThreads;
    private final Duration slotWait;
    private final long maxImageBytes;
    private final ImageSafetyJudge judge;
    // 하루 상한 확인(true면 구글을 불러도 됨). 동시 자리를 잡은 뒤, 구글을 부르기 직전에만 센다.
    private final BooleanSupplier dailyLimit;
    // 동시 호출 자리. 하루 상한을 세기 "전에" 자리부터 잡는다: 자리가 없는데 상한만 깎이는 일을 막으려는 것.
    private final Semaphore slots;
    // 호출 전용 스레드 풀. 동시 수는 slots가 막으므로 풀 자체는 상한을 두지 않고, 줄 세우는 칸도 없다(SynchronousQueue).
    private final ThreadPoolExecutor executor;

    // 자리 대기 없이(0) 만드는 짧은 생성자: 자리가 없으면 바로 UNCHECKED (S30-a 동작)
    public VisionImageSafetyChecker(String baseUrl, String apiKey, Duration timeout, int maxCallThreads,
            long maxImageBytes, ImageSafetyJudge judge, BooleanSupplier dailyLimit) {
        this(baseUrl, apiKey, timeout, maxCallThreads, Duration.ZERO, maxImageBytes, judge, dailyLimit);
    }

    // slotWait: 동시 자리가 가득 찼을 때 자리가 나길 기다리는 최대 시간 (S30-b)
    public VisionImageSafetyChecker(String baseUrl, String apiKey, Duration timeout, int maxCallThreads, Duration slotWait,
            long maxImageBytes, ImageSafetyJudge judge, BooleanSupplier dailyLimit) {
        // 오류 메시지에는 어떤 값도 싣지 않는다(키가 새지 않게)
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.contains("?") || baseUrl.contains("#")) {
            throw new IllegalArgumentException("baseUrl이 올바르지 않습니다.");
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("apiKey가 올바르지 않습니다.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout은 0보다 커야 합니다.");
        }
        if (maxCallThreads < 1 || maxImageBytes < 1) {
            throw new IllegalArgumentException("maxCallThreads와 maxImageBytes는 1 이상이어야 합니다.");
        }
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxCallThreads = maxCallThreads;
        this.slotWait = slotWait == null || slotWait.isNegative() ? Duration.ZERO : slotWait;
        this.slots = new Semaphore(maxCallThreads);
        this.maxImageBytes = maxImageBytes;
        this.judge = judge;
        this.dailyLimit = dailyLimit;
        // 키는 주소(쿼리)가 아니라 헤더로만 보낸다
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            this.endpoint = URI.create(base + "/v1/images:annotate");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl이 올바른 주소가 아닙니다.");
        }
        if (endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw new IllegalArgumentException("baseUrl에 scheme과 host가 있어야 합니다.");
        }

        // 스레드 이름 market-safesearch-1, 2, ... (데몬이라 앱 종료를 막지 않는다)
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "market-safesearch-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        Duration connectTimeout = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
        // 리다이렉트는 따라가지 않는다: 키 헤더가 다른 호스트로 넘어가지 않게
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout.plus(READ_TIMEOUT_MARGIN));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // 키가 로그·화면에 섞여 나오지 않게 키는 빼고 보여 준다
    @Override
    public String toString() {
        return "VisionImageSafetyChecker[maxCallThreads=" + maxCallThreads + "]";
    }

    @Override
    public void close() {
        executor.shutdownNow();
        httpClient.shutdownNow();
    }

    @Override
    public ImageSafetyResult check(byte[] image) {
        if (image == null || image.length == 0) {
            log.warn("사진 검사 건너뜀: 사진이 비어 있음");
            return ImageSafetyResult.unchecked();
        }
        if (image.length > maxImageBytes) {
            log.warn("사진 검사 건너뜀: 사진이 상한 {}바이트보다 큼", maxImageBytes);
            return ImageSafetyResult.unchecked();
        }
        // 1) 동시 자리 먼저: slotWait(기본 2초)만큼만 짧게 기다리고, 그래도 못 잡으면 하루 상한을 건드리지 않고 UNCHECKED.
        // 기다리는 이유: 3명이 동시에 올릴 때 한 명이 검사 없이 통과하는 일을 줄이려고.
        // 시간 계산(업로드 요청 전체는 nginx 150초 안): 이미지 처리 자리 대기 5초 + 자리 대기 2초 + 구글 호출 5초 + 창고 저장.
        boolean gotSlot;
        try {
            gotSlot = slotWait.isZero() ? slots.tryAcquire() : slots.tryAcquire(slotWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 중단 신호를 되살려 둔다
            log.warn("사진 검사 자리 대기 중 중단됨 -> UNCHECKED");
            return ImageSafetyResult.unchecked();
        }
        if (!gotSlot) {
            log.warn("사진 검사 호출 자리 가득 참(상한 {}, 대기 {}ms) -> UNCHECKED", maxCallThreads, slotWait.toMillis());
            return ImageSafetyResult.unchecked();
        }
        // 자리는 작업 스레드가 끝날 때 돌려준다. 어느 길로든 딱 한 번만 돌려주도록 한 번짜리 반납기로 감싼다.
        AtomicBoolean released = new AtomicBoolean();
        Runnable release = () -> {
            if (released.compareAndSet(false, true)) {
                slots.release();
            }
        };
        boolean handedToWorker = false;
        Future<ImageSafetyResult> future;
        try {
            // 2) 하루 상한: 구글을 부르기 직전에 센다. 넘었거나 Redis 고장이면 부르지 않는다.
            boolean allowed;
            try {
                allowed = dailyLimit.getAsBoolean();
            } catch (RuntimeException e) {
                log.warn("사진 검사 하루 상한 확인 실패: {}", e.getClass().getSimpleName());
                return ImageSafetyResult.unchecked();
            }
            if (!allowed) {
                return ImageSafetyResult.unchecked();
            }
            try {
                // 3) base64·JSON 만들기·HTTP·응답 읽기를 전부 작업 스레드 안에서 한다
                future = executor.submit(() -> {
                    try {
                        return checkInWorker(image);
                    } finally {
                        release.run();
                    }
                });
                handedToWorker = true;
            } catch (RejectedExecutionException e) {
                // 닫힌 뒤에만 거절된다(동시 수는 자리로 막았으므로)
                log.warn("사진 검사기가 닫혀 있음 -> UNCHECKED");
                return ImageSafetyResult.unchecked();
            }
        } finally {
            // 작업 스레드에 넘기지 못했으면(상한 걸림·실패·거절) 자리를 바로 돌려준다
            if (!handedToWorker) {
                release.run();
            }
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // 스레드를 깨워 요청도 끊는다
            release.run(); // 시작도 못 하고 취소된 작업은 자기 반납을 못 하니 여기서 돌려준다(한 번만 반납됨)
            log.warn("사진 검사 호출 실패: 전체 상한 초과");
            return ImageSafetyResult.unchecked();
        } catch (ExecutionException e) {
            // 예상 못 한 예외: 종류(클래스 이름)만 남긴다
            log.warn("사진 검사 호출 실패: 예상 못 한 오류 {}", rootClassName(e));
            return ImageSafetyResult.unchecked();
        } catch (InterruptedException e) {
            future.cancel(true);
            release.run();
            Thread.currentThread().interrupt();
            log.warn("사진 검사 호출 실패: 중단됨");
            return ImageSafetyResult.unchecked();
        }
    }

    // 별도 스레드에서 도는 부분. 실패는 로그를 남기고 UNCHECKED로 돌려준다.
    private ImageSafetyResult checkInWorker(byte[] image) {
        byte[] requestBody = buildBody(image);
        byte[] response;
        try {
            response = restClient.post()
                    .uri(endpoint)
                    .header("x-goog-api-key", apiKey)
                    // 응답을 SafeSearch 등급과 오류 코드만으로 줄인다
                    .header("X-Goog-FieldMask", "responses.safeSearchAnnotation,responses.error.code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            // 4xx·5xx: 상태 숫자만 남긴다. 오류 본문과 원본 예외는 버린다.
            int status = e.getStatusCode().value();
            log.warn("사진 검사 호출 실패: HTTP {}{}", status, status == 429 ? " (한도 초과)" : "");
            return ImageSafetyResult.unchecked();
        } catch (RuntimeException e) {
            logConnectionFailure(e);
            return ImageSafetyResult.unchecked();
        }
        return parse(response);
    }

    // 요청 본문: 사진(base64) + SafeSearch 기능
    private byte[] buildBody(byte[] image) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("image", Map.of("content", Base64.getEncoder().encodeToString(image)));
        request.put("features", List.of(Map.of("type", "SAFE_SEARCH_DETECTION")));
        return mapper.writeValueAsBytes(Map.of("requests", List.of(request)));
    }

    // 응답에서 등급을 꺼내 판정한다. 오류·JSON 아님·등급 없음은 모두 UNCHECKED. 응답 본문은 로그에 남기지 않는다.
    private ImageSafetyResult parse(byte[] response) {
        if (response == null) {
            log.warn("사진 검사 응답 사용 불가: 응답 본문이 없음");
            return ImageSafetyResult.unchecked();
        }
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (RuntimeException e) {
            log.warn("사진 검사 응답 사용 불가: JSON이 아님");
            return ImageSafetyResult.unchecked();
        }
        if (root == null || !root.isObject()) {
            log.warn("사진 검사 응답 사용 불가: JSON 객체가 아님");
            return ImageSafetyResult.unchecked();
        }
        JsonNode first = root.path("responses").path(0);
        if (!first.isObject()) {
            log.warn("사진 검사 응답 사용 불가: responses가 비어 있음");
            return ImageSafetyResult.unchecked();
        }
        // 200인데 사진 한 장 단위 오류가 온 경우: 코드 숫자만 남기고 message는 버린다
        JsonNode error = first.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            log.warn("사진 검사 응답 오류: code {}", error.path("code").asInt(-1));
            return ImageSafetyResult.unchecked();
        }
        JsonNode annotation = first.path("safeSearchAnnotation");
        if (!annotation.isObject()) {
            log.warn("사진 검사 응답 사용 불가: 등급이 없음");
            return ImageSafetyResult.unchecked();
        }
        Map<SafetyCategory, SafeSearchLikelihood> levels = new EnumMap<>(SafetyCategory.class);
        levels.put(SafetyCategory.ADULT, level(annotation, "adult"));
        levels.put(SafetyCategory.VIOLENCE, level(annotation, "violence"));
        levels.put(SafetyCategory.RACY, level(annotation, "racy"));
        ImageSafetyResult result = judge.judge(levels);
        // 판정 결과만 한 줄(사진·응답 본문·키·사진 key는 넣지 않는다). UNCHECKED면 판정기가 돌려준 것이라 이유 로그가 필요해 따로 남긴다.
        if (result.verdict() == SafetyVerdict.UNCHECKED) {
            log.warn("사진 검사 응답 사용 불가: 기준이 켜진 항목의 등급이 없음");
        } else {
            // 판정(PASS/FLAG/BLOCK)을 앞에 붙여 FLAG와 BLOCK이 로그에서 구분되게 한다. 사진·응답 본문은 남기지 않는다
            String describe = result.describe();
            log.info("사진 검사: {}", describe.equals(result.verdict().name()) ? describe : result.verdict() + " " + describe);
        }
        return result;
    }

    private static SafeSearchLikelihood level(JsonNode annotation, String field) {
        JsonNode node = annotation.path(field);
        return node.isString() ? SafeSearchLikelihood.fromApi(node.asString()) : SafeSearchLikelihood.UNKNOWN;
    }

    // 연결·요청 오류를 로그로 남긴다. 시간 초과 때 끊겨서 생긴 오류는 이미 Future 쪽이 처리하므로 조용히 넘어간다.
    private static void logConnectionFailure(RuntimeException e) {
        boolean interrupted = Thread.currentThread().isInterrupted();
        for (Throwable t = e; t != null && !interrupted; t = t.getCause()) {
            interrupted = t instanceof InterruptedException;
        }
        if (interrupted) {
            return;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                log.warn("사진 검사 호출 실패: 연결 시간 초과");
                return;
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                log.warn("사진 검사 호출 실패: 응답 시간 초과");
                return;
            }
        }
        log.warn("사진 검사 호출 실패: 연결 또는 요청 오류 {}", rootClassName(e));
    }

    // 원인 사슬 맨 끝 예외의 클래스 이름(메시지는 쓰지 않는다)
    private static String rootClassName(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getName();
    }
}
