package com.example.demo.permit.service;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 구글 Cloud Vision(images:annotate)으로 허가서 사진의 글자를 읽는 판독기 (S20-e1).
// 스프링 빈 등록은 PermitOcrConfig(S20-e2)가 한다(PERMIT_OCR_MODE=vision일 때만).
// 만든 방식은 GeminiLlmClient와 같다: 호출을 별도 스레드에서 돌리고 "시간 초과" 판정은 Future.get 한 곳에서만 한다.
// 보안·개인정보 원칙: API 키, 사진 바이트, base64, 응답 본문, 읽은 글자, 오류 message는 로그·예외에 절대 넣지 않는다.
// 로그에는 실패 종류, HTTP 상태 숫자, 예외 클래스 이름, error.code 숫자만 남긴다.
// 못 읽은 모든 경우는 예외 대신 Optional.empty()로 돌려준다(호출하는 쪽이 "못 읽음"으로 처리한다).
public class VisionPermitTextReader implements PermitTextReader, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VisionPermitTextReader.class);

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
    private final long maxImageBytes;
    // 호출 전용 스레드 풀. 줄 세우는 칸이 없어서(SynchronousQueue) 가득 차면 기다리지 않고 바로 거절한다.
    private final ThreadPoolExecutor executor;

    public VisionPermitTextReader(String baseUrl, String apiKey, Duration timeout, int maxCallThreads, long maxImageBytes) {
        // 오류 메시지에는 어떤 값도 싣지 않는다(키가 새지 않게)
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl은 비어 있을 수 없습니다.");
        }
        if (baseUrl.contains("?") || baseUrl.contains("#")) {
            throw new IllegalArgumentException("baseUrl에는 ?와 #을 쓸 수 없습니다.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey는 비어 있을 수 없습니다.");
        }
        // 키에 줄바꿈 같은 제어 글자가 있으면 헤더로 못 보낸다. 호출 때 터지기 전에 만들 때 막는다.
        if (apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("apiKey에 제어 글자(줄바꿈 등)가 있습니다.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout은 0보다 커야 합니다.");
        }
        if (maxCallThreads < 1) {
            throw new IllegalArgumentException("maxCallThreads는 1 이상이어야 합니다.");
        }
        if (maxImageBytes < 1) {
            throw new IllegalArgumentException("maxImageBytes는 1 이상이어야 합니다.");
        }
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxCallThreads = maxCallThreads;
        this.maxImageBytes = maxImageBytes;
        // 키는 주소(쿼리)가 아니라 헤더로만 보낸다. 주소는 baseUrl + 고정 경로.
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            this.endpoint = URI.create(base + "/v1/images:annotate");
        } catch (IllegalArgumentException e) {
            // 원본 메시지에 주소가 들어 있으므로 값 없는 메시지로 바꾼다
            throw new IllegalArgumentException("baseUrl이 올바른 주소가 아닙니다.");
        }
        // http://처럼 scheme·host가 빠진 주소는 호출 때 터지기 전에 만들 때 막는다(https 강제는 e2 설정 클래스 몫)
        if (endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw new IllegalArgumentException("baseUrl에 scheme과 host가 있어야 합니다.");
        }

        // 스레드 이름 vision-reader-1, 2, ... (데몬이라 앱 종료를 막지 않는다)
        AtomicInteger threadNumber = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(0, maxCallThreads, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "vision-reader-" + threadNumber.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        Duration connectTimeout = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
        // 리다이렉트는 따라가지 않는다: 키 헤더가 다른 호스트로 넘어가지 않게(명시해 두는 것)
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
        return "VisionPermitTextReader[maxCallThreads=" + maxCallThreads + "]";
    }

    // 빈이 사라질 때(e2) 호출 스레드를 정리할 수 있게 한다
    @Override
    public void close() {
        executor.shutdownNow();
        httpClient.shutdownNow(); // Java 21: 진행 중인 요청을 기다리지 않고 바로 끊는다(close()는 기다린다)
    }

    // contentType은 쓰지 않는다: Vision은 사진 바이트를 보고 형식을 스스로 알아낸다.
    @Override
    public Optional<String> read(byte[] image, String contentType) {
        if (image == null || image.length == 0) {
            log.warn("vision 판독 건너뜀: 사진이 비어 있음");
            return Optional.empty();
        }
        // 너무 큰 사진은 호출하지 않는다(메모리·요금 보호). 크기 숫자는 개인정보가 아니라 로그에 남겨도 된다.
        if (image.length > maxImageBytes) {
            log.warn("vision 판독 건너뜀: 사진이 상한 {}바이트보다 큼", maxImageBytes);
            return Optional.empty();
        }
        Future<Optional<String>> future;
        try {
            // base64·JSON 만들기·HTTP·응답 읽기를 전부 작업 스레드 안에서 한다(메모리 부족 같은 Error도 ExecutionException으로 감싸지게)
            future = executor.submit(() -> readInWorker(image));
        } catch (RejectedExecutionException e) {
            // 이미 닫힌 판독기인지, 자리가 꽉 찬 것인지 로그에서 구분한다
            if (executor.isShutdown()) {
                log.warn("vision 판독기가 닫혀 있음 -> 못 읽음");
            } else {
                log.warn("vision 호출 풀 가득 참(상한 {}) -> 못 읽음", maxCallThreads);
            }
            return Optional.empty();
        }
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // 스레드를 깨워 요청도 끊는다
            log.warn("vision 호출 실패: 전체 상한 초과");
            return Optional.empty();
        } catch (ExecutionException e) {
            // 작업 스레드에서 예상 못 한 예외·Error가 난 경우. 종류(클래스 이름)만 남긴다.
            log.warn("vision 호출 실패: 예상 못 한 오류 {}", rootClassName(e));
            return Optional.empty();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            log.warn("vision 호출 실패: 중단됨");
            return Optional.empty();
        }
    }

    // 별도 스레드에서 도는 부분. 실패는 로그를 남기고 empty로 돌려준다.
    private Optional<String> readInWorker(byte[] image) {
        byte[] requestBody = buildBody(image);
        byte[] response;
        try {
            response = restClient.post()
                    .uri(endpoint)
                    .header("x-goog-api-key", apiKey)
                    // 응답을 글자와 오류 코드만으로 줄인다(error.message도 안 옴).
                    // Vision v1에서 이 헤더는 S20-h 실물 시험에서 실제로 먹는 것을 확인했다.
                    .header("X-Goog-FieldMask", "responses.fullTextAnnotation.text,responses.error.code")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            // 4xx·5xx: 상태 숫자만 남긴다. 오류 본문과 원본 예외는 버린다.
            int status = e.getStatusCode().value();
            log.warn("vision 호출 실패: HTTP {}{}", status, status == 429 ? " (한도 초과)" : "");
            return Optional.empty();
        } catch (RuntimeException e) {
            logConnectionFailure(e);
            return Optional.empty();
        }
        return parse(response);
    }

    // 요청 본문: 사진(base64) + 문서 글자 인식 + 한국어·영어 힌트
    private byte[] buildBody(byte[] image) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("image", Map.of("content", Base64.getEncoder().encodeToString(image)));
        request.put("features", List.of(Map.of("type", "DOCUMENT_TEXT_DETECTION")));
        request.put("imageContext", Map.of("languageHints", List.of("ko", "en")));
        return mapper.writeValueAsBytes(Map.of("requests", List.of(request)));
    }

    // 응답에서 글자를 꺼낸다. 글자 없음·오류·JSON 아님은 모두 empty. 읽은 글자는 로그에 남기지 않는다.
    private Optional<String> parse(byte[] response) {
        if (response == null) {
            log.warn("vision 응답 사용 불가: 응답 본문이 없음");
            return Optional.empty();
        }
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (RuntimeException e) {
            log.warn("vision 응답 사용 불가: JSON이 아님");
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            log.warn("vision 응답 사용 불가: JSON 객체가 아님");
            return Optional.empty();
        }
        JsonNode first = root.path("responses").path(0);
        if (!first.isObject()) {
            log.warn("vision 응답 사용 불가: responses가 비어 있음");
            return Optional.empty();
        }
        // 200인데 사진 한 장 단위 오류가 온 경우: 코드 숫자만 남기고 message는 버린다
        JsonNode error = first.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            log.warn("vision 응답 오류: code {}", error.path("code").asInt(-1));
            return Optional.empty();
        }
        JsonNode text = first.path("fullTextAnnotation").path("text");
        if (!text.isString() || text.asString().isBlank()) {
            return Optional.empty(); // 사진에서 글자를 못 찾음(정상 응답)
        }
        return Optional.of(text.asString()); // 줄바꿈은 그대로 둔다
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
                log.warn("vision 호출 실패: 연결 시간 초과");
                return;
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                log.warn("vision 호출 실패: 응답 시간 초과");
                return;
            }
        }
        log.warn("vision 호출 실패: 연결 또는 요청 오류 {}", rootClassName(e));
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
