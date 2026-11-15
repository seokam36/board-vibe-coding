package com.example.demo.llm.service;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 구글 제미나이(generateContent)로 질문하는 구현. 아직 어디에도 연결하지 않았다(연결은 S18-c).
// 만든 방식은 OllamaLlmClient와 같다: 호출을 별도 스레드에서 돌리고 전체 제한 시간을 Future 하나로 잰다.
// 보안 원칙: API 키·오류 본문·주소 쿼리가 예외 메시지·원인(cause)·로그에 절대 들어가지 않게 한다.
// 그래서 외부 라이브러리 예외는 cause로 달지 않고 버린다(그 메시지에 오류 본문이나 주소가 들어 있을 수 있다).
public class GeminiLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiLlmClient.class);

    // 연결 제한: 서버에 닿지 않을 때 오래 기다리지 않게 호출 제한과 따로 짧게 둔다(Ollama 구현과 같은 이유)
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    // 읽기 제한은 전체 상한보다 일부러 길게: "시간 초과" 판정을 Future 한 곳에서만 하려는 것(Ollama 구현 주석 참고)
    private static final Duration READ_TIMEOUT_MARGIN = Duration.ofSeconds(5);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestClient restClient;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final int maxCallThreads;
    // 호출 전용 스레드 풀. 줄 세우는 칸이 없어서(SynchronousQueue) 가득 차면 기다리지 않고 바로 거절한다.
    private final ThreadPoolExecutor executor;

    public GeminiLlmClient(String baseUrl, String apiKey, String model, Duration timeout, int maxCallThreads) {
        // 메시지에는 어떤 값도 싣지 않는다(키가 새지 않게)
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl은 비어 있을 수 없습니다.");
        }
        // 주소에 ?·#이 있으면 뒤에 붙는 경로가 쿼리로 먹힌다. 쿼리 자체도 쓰지 않으므로 막는다.
        if (baseUrl.contains("?") || baseUrl.contains("#")) {
            throw new IllegalArgumentException("baseUrl에는 ?와 #을 쓸 수 없습니다.");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey는 비어 있을 수 없습니다.");
        }
        // 키에 줄바꿈 같은 제어 글자가 있으면(.env 끝 줄바꿈 등) 헤더로 못 보낸다. 호출 때 터지기 전에 만들 때 막는다.
        if (apiKey.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("apiKey에 제어 글자(줄바꿈 등)가 있습니다.");
        }
        // 모델 이름은 주소 경로에 그대로 들어간다: 글자·숫자·점·밑줄·하이픈만 허용(/ ? 공백으로 경로가 바뀌는 것을 막는다)
        if (model == null || !model.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("model은 영문·숫자·.·_·- 로만 이루어져야 합니다.");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout은 0보다 커야 합니다.");
        }
        if (maxCallThreads < 1) {
            throw new IllegalArgumentException("maxCallThreads는 1 이상이어야 합니다.");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.maxCallThreads = maxCallThreads;
        // 키는 주소(쿼리)가 아니라 헤더로만 보낸다. 주소는 baseUrl + 고정 경로.
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        try {
            this.endpoint = URI.create(base + "/v1beta/models/" + model + ":generateContent");
        } catch (IllegalArgumentException e) {
            // 원본 메시지에 주소가 들어 있으므로 값 없는 메시지로 바꾼다
            throw new IllegalArgumentException("baseUrl이 올바른 주소가 아닙니다.");
        }

        this.executor = new ThreadPoolExecutor(0, maxCallThreads, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "gemini-call");
            t.setDaemon(true);
            return t;
        });
        Duration connectTimeout = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout.plus(READ_TIMEOUT_MARGIN));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    // 호출 스레드 상한(확인용)
    public int maxCallThreads() {
        return maxCallThreads;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    // 키가 로그·화면에 섞여 나오지 않게 키는 빼고 모델 이름만 보여 준다
    @Override
    public String toString() {
        return "GeminiLlmClient[model=" + model + "]";
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Duration callTimeout) {
        checkTexts(systemPrompt, userMessage);
        return call(buildBody(systemPrompt, userMessage, null), callTimeout);
    }

    @Override
    public String chatJson(String systemPrompt, String userMessage, String jsonSchema, Duration callTimeout) {
        checkTexts(systemPrompt, userMessage);
        if (jsonSchema == null || jsonSchema.isBlank()) {
            throw new IllegalArgumentException("jsonSchema는 비어 있을 수 없습니다.");
        }
        // 스키마는 글자가 아니라 JSON 객체로 실어야 한다. 파싱이 안 되거나 객체가 아니면 호출 전에 잘못된 인자로 알린다(원문은 메시지에 없음).
        JsonNode schema = null;
        try {
            schema = mapper.readTree(jsonSchema);
        } catch (RuntimeException e) {
            // 파싱 실패는 아래에서 "객체가 아님"과 같이 처리한다
        }
        if (schema == null || !schema.isObject()) {
            throw new IllegalArgumentException("jsonSchema가 올바른 JSON이 아닙니다.");
        }
        return call(buildBody(systemPrompt, userMessage, schema), callTimeout);
    }

    private static void checkTexts(String systemPrompt, String userMessage) {
        if (systemPrompt == null || systemPrompt.isBlank() || userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("systemPrompt와 userMessage는 비어 있을 수 없습니다.");
        }
    }

    // 요청 본문을 Map으로 만든다. schema가 있으면(chatJson) "JSON으로만 답하라"는 설정을 더 싣는다.
    private byte[] buildBody(String systemPrompt, String userMessage, JsonNode schema) {
        Map<String, Object> generationConfig = new LinkedHashMap<>();
        // 생각 과정은 최소로(S18 실측: 느려지지 않게). thinkingLevel은 제미나이 3.x 계열 전용 설정이다(2.5 이하는 다른 설정).
        generationConfig.put("thinkingConfig", Map.of("thinkingLevel", "minimal"));
        if (schema != null) {
            generationConfig.put("responseMimeType", "application/json");
            generationConfig.put("responseJsonSchema", schema);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", userMessage)))));
        body.put("generationConfig", generationConfig);
        return mapper.writeValueAsBytes(body);
    }

    private String call(byte[] requestBody, Duration callTimeout) {
        if (callTimeout == null || callTimeout.isZero() || callTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout은 0보다 커야 합니다.");
        }
        // 넘긴 값이 생성자 제한보다 길면 생성자 제한까지만 기다린다
        Duration limit = callTimeout.compareTo(timeout) > 0 ? timeout : callTimeout;
        long startNanos = System.nanoTime();
        Future<String> future;
        try {
            future = executor.submit(() -> send(requestBody));
        } catch (RejectedExecutionException e) {
            // 호출 스레드가 상한까지 찼다: 기다리지 않고 사용 불가로 바로 알린다(숫자만 로그)
            log.warn("gemini-call 풀 가득 참(상한 {}) -> 사용 불가", maxCallThreads);
            throw new LlmUnavailableException("Gemini 호출 자리가 가득 찼습니다.");
        }
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // 스레드를 깨워 요청도 끊는다
            throw new LlmTimeoutException("Gemini 응답이 제한 시간을 넘겼습니다.", null); // 원인은 일부러 안 단다
        } catch (ExecutionException e) {
            // 제한 시간만큼 기다린 뒤의 실패는 시간 초과로 본다(연결을 닫아 생긴 IOException 등에 흔들리지 않게)
            if (System.nanoTime() - startNanos >= limit.toNanos()) {
                throw new LlmTimeoutException("Gemini 응답이 제한 시간을 넘겼습니다.", null);
            }
            // send()가 이미 우리 예외로 바꿔 던진다. 그 밖의 예상 못 한 예외는 원인 없이 사용 불가로.
            if (e.getCause() instanceof LlmUnavailableException || e.getCause() instanceof LlmTimeoutException) {
                throw (RuntimeException) e.getCause();
            }
            // 종류(클래스 이름)만 로그에 남긴다. 메시지에는 비밀이 있을 수 있다.
            log.warn("gemini 호출 실패: 예상 못 한 오류 {}", rootClassName(e));
            throw new LlmUnavailableException("Gemini 호출에 실패했습니다.");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Gemini 호출이 중단되었습니다.");
        }
    }

    // 별도 스레드에서 도는 부분: HTTP 호출 + 응답 읽기. 모든 실패를 (원인 없는) 우리 예외로 바꾼다.
    private String send(byte[] requestBody) {
        byte[] response;
        try {
            response = restClient.post()
                    .uri(endpoint)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            // 4xx·5xx: 상태 숫자만 남긴다. 오류 본문(message)과 원본 예외는 버린다.
            int status = e.getStatusCode().value();
            log.warn("gemini 호출 실패: HTTP {}{}", status, status == 429 ? " (한도 초과)" : "");
            throw new LlmUnavailableException("Gemini가 오류로 답했습니다. (HTTP " + status + ")");
        } catch (RuntimeException e) {
            throw translate(e);
        }
        return readText(response);
    }

    // 응답에서 글자를 꺼낸다. 차단·후보 없음·STOP 아님·빈 글자·JSON 아님은 모두 사용 불가.
    private String readText(byte[] response) {
        if (response == null) {
            throw unavailable("응답 본문이 없습니다.");
        }
        JsonNode root;
        try {
            root = mapper.readTree(response);
        } catch (RuntimeException e) {
            throw unavailable("응답이 JSON이 아닙니다.");
        }
        if (root == null || !root.isObject()) {
            throw unavailable("응답이 JSON 객체가 아닙니다.");
        }
        if (!root.path("promptFeedback").path("blockReason").asString("").isBlank()) {
            throw unavailable("질문이 차단되었습니다.");
        }
        JsonNode candidates = root.path("candidates");
        if (!candidates.isArray() || candidates.size() == 0) {
            throw unavailable("답 후보가 없습니다.");
        }
        JsonNode candidate = candidates.get(0);
        if (!"STOP".equals(candidate.path("finishReason").asString(""))) {
            throw unavailable("답이 정상으로 끝나지 않았습니다.");
        }
        // 여러 part면 이어 붙인다. 생각(thought) part는 답이 아니므로 건너뛴다.
        StringBuilder text = new StringBuilder();
        for (JsonNode part : candidate.path("content").path("parts")) {
            if (!part.path("thought").asBoolean(false)) {
                text.append(part.path("text").asString(""));
            }
        }
        if (text.toString().isBlank()) {
            throw unavailable("빈 답을 돌려줬습니다.");
        }
        return text.toString();
    }

    private static LlmUnavailableException unavailable(String reason) {
        log.warn("gemini 응답 사용 불가: {}", reason);
        return new LlmUnavailableException("Gemini 응답을 쓸 수 없습니다. (" + reason + ")");
    }

    // 원인 사슬에서 시간 초과를 찾는다. 연결 시간 초과는 서버 없음과 같으므로 사용 불가. 원본 예외는 달지 않는다(주소·키가 들어 있을 수 있음).
    private static RuntimeException translate(RuntimeException e) {
        // 시간 초과로 cancel(true)된 스레드는 "중단됨" 오류로 끝난다. 이미 호출 쪽이 시간 초과로 처리했으니 로그를 더 찍지 않는다.
        boolean interrupted = Thread.currentThread().isInterrupted();
        for (Throwable t = e; t != null && !interrupted; t = t.getCause()) {
            interrupted = t instanceof InterruptedException;
        }
        if (interrupted) {
            return new LlmUnavailableException("Gemini 호출이 중단되었습니다.");
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                log.warn("gemini 호출 실패: 연결 시간 초과");
                return new LlmUnavailableException("Gemini 서버에 연결하지 못했습니다.");
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return new LlmTimeoutException("Gemini 응답이 제한 시간을 넘겼습니다.", null);
            }
        }
        log.warn("gemini 호출 실패: 연결 또는 요청 오류 {}", rootClassName(e));
        return new LlmUnavailableException("Gemini 호출에 실패했습니다.");
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
