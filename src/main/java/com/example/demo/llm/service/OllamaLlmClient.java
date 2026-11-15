package com.example.demo.llm.service;

import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

// LLM_MODE=ollama일 때의 구현. 내 PC에서 도는 Ollama 서버에 Spring AI로 질문한다.
// Spring AI 클래스는 이 파일 안에만 있다(서비스 코드는 LlmClient만 안다).
public class OllamaLlmClient implements LlmClient {

    // 연결 제한은 호출 제한(보통 60초)과 따로 짧게 둔다: 서버가 꺼져 있을 때 1분을 기다리지 않게 하고,
    // "서버 없음(503)"과 "응답이 느림(504)" 구분이 흔들리지 않게 하려는 것이다.
    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final OllamaChatModel chatModel;
    private final String model;
    private final Duration timeout;
    // 호출을 따로 돌릴 스레드 풀. 데몬 스레드라 앱 종료를 막지 않는다.
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ollama-call");
        t.setDaemon(true);
        return t;
    });

    public OllamaLlmClient(String baseUrl, String model, Duration timeout) {
        // 제한 시간은 Ollama용 RestClient에만 건다. 스프링 전역 http 설정(spring.http.clients.*)을 쓰면 다른 HTTP 클라이언트까지 영향을 받는다.
        // 읽기 제한은 "응답 헤더가 오기까지"만 잰다. 본문이 천천히 흘러오는 경우는 chat()에서 호출 전체에 상한을 따로 건다.
        this.model = model;
        this.timeout = timeout;
        Duration connectTimeout = timeout.compareTo(MAX_CONNECT_TIMEOUT) < 0 ? timeout : MAX_CONNECT_TIMEOUT;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);

        OllamaApi api = OllamaApi.builder().baseUrl(baseUrl).restClientBuilder(restClientBuilder).build();
        this.chatModel = OllamaChatModel.builder()
                .ollamaApi(api)
                // 생각 과정(think)은 끈다: S17-a 실측에서 켜면 느려지고 답에 섞였다
                .options(OllamaChatOptions.builder().model(model).disableThinking().build())
                // Spring AI 기본은 최대 10회 재시도(재시도 사이 대기가 최대 3분씩이라 10번이면 합쳐서 수십 분). 재시도 0회 = 한 번만 시도하고 바로 실패를 올린다
                .retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)))
                .build();
    }

    @Override
    public String chat(String systemPrompt, String userMessage) {
        return chat(systemPrompt, userMessage, timeout);
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Duration callTimeout) {
        checkTexts(systemPrompt, userMessage);
        // 옵션을 따로 넘기지 않는다 = 생성자에서 정한 기본 옵션(model, think 끔)만 쓴다. format은 실리지 않는다.
        return call(new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage))), callTimeout);
    }

    @Override
    public String chatJson(String systemPrompt, String userMessage, String jsonSchema, Duration callTimeout) {
        checkTexts(systemPrompt, userMessage);
        if (jsonSchema == null || jsonSchema.isBlank()) {
            throw new IllegalArgumentException("jsonSchema는 비어 있을 수 없습니다.");
        }
        // outputSchema = Ollama 요청의 format 칸에 JSON Schema를 넣는다(모델이 그 모양으로만 답하게 강제).
        // 호출 옵션은 기본 옵션과 합쳐진다고 믿지 않고 model·think 끔을 다시 적는다.
        // Spring AI는 outputSchema()에서 스키마 글자를 바로 JSON으로 파싱한다. JSON이 아니면 여기서 예외가 나므로
        // 호출 쪽 잘못(잘못된 인자)으로 통일해 알린다. 메시지에 스키마 원문은 넣지 않는다.
        OllamaChatOptions options;
        try {
            options = OllamaChatOptions.builder()
                    .model(model)
                    .disableThinking()
                    .outputSchema(jsonSchema)
                    .build();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("jsonSchema가 올바른 JSON이 아닙니다.");
        }
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userMessage)), options);
        return call(prompt, callTimeout);
    }

    // 빈 인자는 호출 쪽 버그다. "AI 사용 불가"로 숨기지 않고 바로 IllegalArgumentException으로 알린다.
    private static void checkTexts(String systemPrompt, String userMessage) {
        if (systemPrompt == null || systemPrompt.isBlank() || userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("systemPrompt와 userMessage는 비어 있을 수 없습니다.");
        }
    }

    private String call(Prompt prompt, Duration callTimeout) {
        if (callTimeout == null || callTimeout.isZero() || callTimeout.isNegative()) {
            throw new IllegalArgumentException("timeout은 0보다 커야 합니다.");
        }
        // 읽기 제한이 생성자 값이라 그보다 길게는 기다릴 수 없다: 생성자 값을 상한으로 쓴다.
        Duration limit = callTimeout.compareTo(timeout) > 0 ? timeout : callTimeout;
        // 호출을 별도 스레드에서 돌리고 전체 제한 시간만 기다린다. 넘기면 cancel(true)로 스레드를 깨워 요청도 끊는다.
        Future<ChatResponse> future = executor.submit(() -> chatModel.call(prompt));
        try {
            ChatResponse response = future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
            String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new LlmUnavailableException("Ollama가 빈 답을 돌려줬습니다.");
            }
            return text;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new LlmTimeoutException("Ollama 응답이 제한 시간을 넘겼습니다.", e);
        } catch (ExecutionException e) {
            // Spring AI·RestClient 예외를 우리 예외 2개로 바꿔서 위로 올린다
            Throwable cause = e.getCause();
            throw translate(cause instanceof RuntimeException re ? re : new RuntimeException(cause));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("Ollama 호출이 중단되었습니다.", e);
        }
    }

    // 원인 사슬을 따라가며 "시간 초과"인지 찾는다. 연결 시간 초과는 서버가 없는 것과 같으므로 "사용 불가"로 본다.
    static RuntimeException translate(RuntimeException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpConnectTimeoutException) {
                return new LlmUnavailableException("Ollama 서버에 연결하지 못했습니다.", e);
            }
            if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
                return new LlmTimeoutException("Ollama 응답이 제한 시간을 넘겼습니다.", e);
            }
        }
        return new LlmUnavailableException("Ollama 호출에 실패했습니다.", e);
    }
}
