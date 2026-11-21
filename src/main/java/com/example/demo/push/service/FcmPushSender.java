package com.example.demo.push.service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

// 구글 FCM(HTTP v1)으로 푸시 알림 한 건을 보내는 발송기 (S24-b). 빈 등록은 PushConfig(PUSH_MODE=fcm일 때만).
// POST {base-url}/v1/projects/{projectId}/messages:send, 헤더 Authorization: Bearer <액세스 토큰>
// 보안 원칙: 기기 토큰·액세스 토큰·키 내용·응답 본문·예외 message는 로그에 절대 넣지 않는다.
// 로그에는 HTTP 상태 숫자, errorCode(UNREGISTERED) 여부, 예외 클래스 이름만 남긴다.
// 모든 실패는 예외 대신 PushResult로 돌려준다.
public class FcmPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(FcmPushSender.class);

    // 연결이 안 닿을 때 오래 기다리지 않게 전체 상한과 따로 짧게 둔다
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final AccessTokenProvider tokenProvider;
    private final RestClient restClient;
    private final URI endpoint;
    private final HttpClient httpClient;

    public FcmPushSender(String baseUrl, String projectId, Duration timeout, AccessTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.endpoint = URI.create(base + "/v1/projects/" + projectId + "/messages:send");

        Duration connectTimeout = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
        // 리다이렉트는 따라가지 않는다: Authorization 헤더가 다른 호스트로 넘어가지 않게
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public boolean enabled() {
        return true;
    }

    // 앱이 꺼질 때(빈이 사라질 때) 연결을 정리한다
    public void close() {
        httpClient.shutdownNow(); // Java 21: 진행 중인 요청을 기다리지 않고 바로 끊는다
    }

    @Override
    public PushResult send(String token, String title, String body) {
        // 0) 값이 비었으면 예외 대신 FAILED (값은 로그에 안 남긴다)
        if (token == null || token.isBlank() || title == null || title.isBlank() || body == null || body.isBlank()) {
            log.warn("fcm 발송 실패: token·title·body 중 빈 값이 있음");
            return PushResult.FAILED;
        }

        // 1) 출입증 받기
        String accessToken;
        try {
            accessToken = tokenProvider.accessToken();
        } catch (IOException | RuntimeException e) {
            log.warn("fcm 발송 실패: 액세스 토큰을 못 받음 {}", e.getClass().getSimpleName());
            return PushResult.FAILED;
        }

        // 2) 구글에 보내기
        Map<String, Object> message = Map.of("message", Map.of(
                "token", token,
                "notification", Map.of("title", title, "body", body)));
        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(message)
                    .retrieve()
                    .toBodilessEntity();
            // 리다이렉트(3xx)는 오류로 안 잡히므로 2xx일 때만 보냄으로 본다 (아니면 알림이 안 갔는데 SENT가 됨)
            if (!response.getStatusCode().is2xxSuccessful()) {
                log.warn("fcm 발송 실패: 예상 밖 HTTP {}", response.getStatusCode().value());
                return PushResult.FAILED;
            }
            return PushResult.SENT;
        } catch (RestClientResponseException e) {
            return handleErrorResponse(e);
        } catch (RuntimeException e) {
            logConnectionFailure(e);
            return PushResult.FAILED;
        }
    }

    // 4xx·5xx 처리. 404 + errorCode UNREGISTERED만 "이 토큰은 죽었다"로 본다.
    // (404여도 UNREGISTERED가 없으면 프로젝트 ID 같은 설정 실수일 수 있어, 토큰을 지우지 않도록 FAILED로 둔다)
    private PushResult handleErrorResponse(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 404 && e.getResponseBodyAsString().contains("UNREGISTERED")) {
            log.info("fcm 발송: 기기 토큰이 더 이상 유효하지 않음(HTTP 404 UNREGISTERED)");
            return PushResult.TOKEN_INVALID;
        }
        log.warn("fcm 발송 실패: HTTP {}{}", status, status == 429 ? " (한도 초과)" : "");
        return PushResult.FAILED;
    }

    // 연결·시간 초과 같은 오류를 로그로 남긴다(메시지는 안 씀: 주소·값이 섞일 수 있다)
    private static void logConnectionFailure(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                log.warn("fcm 발송 실패: 연결 시간 초과");
                return;
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                log.warn("fcm 발송 실패: 응답 시간 초과");
                return;
            }
        }
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        log.warn("fcm 발송 실패: 연결 또는 요청 오류 {}", root.getClass().getName());
    }

    @Override
    public String toString() {
        return "FcmPushSender";
    }
}
