package com.example.demo.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.llm.service.GeminiLlmClient;
import com.example.demo.llm.service.LlmClient;
import com.example.demo.llm.service.OffLlmClient;
import com.example.demo.llm.service.OllamaLlmClient;

// LLM_MODE 설정으로 AI 구현을 갈아 끼운다: off(기본) = 꺼짐, ollama = 내 PC의 Ollama, gemini = 구글 제미나이 API.
// 이 클래스의 생성자가 시작 때 모드와 설정 값을 검사한다. 이상하면 어떤 변수가 문제인지 알려 주며 시작이 실패한다(값 자체는 출력 안 함. 키가 새지 않게).
// Ollama 서버가 꺼져 있어도, 구글에 닿지 않아도 시작은 성공한다(연결은 질문할 때 처음 한다).
@Configuration
// ChatProperties도 여기서 등록한다(ChatConfig와 겹쳐 등록해도 같은 빈으로 합쳐진다)
@EnableConfigurationProperties({ LlmProperties.class, ChatProperties.class })
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    // gemini 주소에서 http(암호화 없음)를 허락하는 곳: 내 컴퓨터 안(테스트의 가짜 서버)뿐
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    public LlmConfig(LlmProperties props) {
        String mode = props.mode();
        if (!"off".equals(mode) && !"ollama".equals(mode) && !"gemini".equals(mode)) {
            throw new IllegalStateException("LLM_MODE는 off, ollama, gemini 중 하나여야 합니다 (현재 값은 출력하지 않음).");
        }
        if ("off".equals(mode)) {
            // 시작할 때 한 번만 알린다(요청마다 로그를 남기지 않는다). 설정 값·비밀은 출력하지 않는다.
            log.info("AI 꺼짐(LLM_MODE=off): /chat은 항상 503을 돌려줍니다.");
        }
        if ("ollama".equals(mode)) {
            // ollama 모드는 설정 값이 쓸 수 있는지 시작 때 확인한다(어떤 변수인지만 알리고 값은 출력 안 함)
            LlmProperties.Ollama o = props.ollama();
            if (o.timeout() == null || o.timeout().isZero() || o.timeout().isNegative()) {
                throw new IllegalStateException("OLLAMA_TIMEOUT은 0보다 커야 합니다 (단위 없으면 초, 예: 60 또는 60s).");
            }
            if (o.baseUrl() == null || o.baseUrl().isBlank()) {
                throw new IllegalStateException("LLM_MODE=ollama인데 OLLAMA_BASE_URL이 비어 있습니다.");
            }
            if (o.model() == null || o.model().isBlank()) {
                throw new IllegalStateException("LLM_MODE=ollama인데 OLLAMA_MODEL이 비어 있습니다.");
            }
        }
        if ("gemini".equals(mode)) {
            checkGemini(props.gemini());
        }
    }

    // gemini 모드 설정 검사. 메시지에는 변수 이름만 쓰고 값(특히 키)은 절대 쓰지 않는다.
    private static void checkGemini(LlmProperties.Gemini g) {
        // 키·모델 검사는 GeminiLlmClient 생성자와 일부러 두 겹이다: 여기는 어느 설정(변수 이름)이
        // 틀렸는지 알려 주고, 클라이언트는 자체 방어다. 모델 정규식은 두 곳이 같아야 한다.
        String key = g.apiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("LLM_MODE=gemini인데 GEMINI_API_KEY가 비어 있습니다.");
        }
        // 공백·줄바꿈 같은 글자가 끼면(.env 복사 실수) 헤더로 못 보낸다. 호출 때 터지기 전에 시작 때 막는다.
        if (key.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
            throw new IllegalStateException("GEMINI_API_KEY에 공백이나 줄바꿈 같은 글자가 들어 있습니다.");
        }
        // 모델 이름은 주소 경로에 그대로 들어간다(models/ 접두사나 / 가 있으면 경로가 바뀐다)
        if (g.model() == null || !g.model().matches("[A-Za-z0-9._-]+")) {
            throw new IllegalStateException("GEMINI_MODEL은 영문·숫자·.·_·- 로만 이루어져야 합니다 (models/ 접두사 없이, 예: gemini-3.5-flash-lite).");
        }
        Duration timeout = g.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("GEMINI_TIMEOUT은 0보다 커야 합니다 (단위 없으면 초, 예: 30 또는 30s).");
        }
        checkGeminiBaseUrl(g.baseUrl());
    }

    // 키를 헤더에 실어 보내는 주소다. 암호화 없는 http로 바깥에 보내면 키가 샌다: https만 허용하고, 내 컴퓨터(가짜 서버)만 http도 허용한다.
    private static void checkGeminiBaseUrl(String baseUrl) {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.llm.gemini.base-url이 올바른 주소가 아닙니다.");
        }
        boolean https = "https".equals(uri.getScheme());
        boolean localHttp = "http".equals(uri.getScheme()) && uri.getHost() != null && LOCAL_HOSTS.contains(uri.getHost());
        if (uri.getHost() == null || !(https || localHttp)) {
            throw new IllegalStateException("app.llm.gemini.base-url은 https 주소여야 합니다.");
        }
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.mode", havingValue = "off", matchIfMissing = true)
    LlmClient offLlmClient() {
        return new OffLlmClient();
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.mode", havingValue = "ollama")
    LlmClient ollamaLlmClient(LlmProperties props, ChatProperties chat) {
        LlmProperties.Ollama o = props.ollama();
        // 호출 스레드 상한 = 동시 질문 상한 + 2. 여유 2는 시간 초과로 취소된 호출 스레드가 아직 정리 중일 때를 위한 것.
        // llm 패키지가 chat 설정을 직접 알지 않도록 여기서 계산해 넘긴다.
        return new OllamaLlmClient(o.baseUrl(), o.model(), o.timeout(), chat.maxConcurrent() + 2);
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.mode", havingValue = "gemini")
    LlmClient geminiLlmClient(LlmProperties props, ChatProperties chat) {
        LlmProperties.Gemini g = props.gemini();
        // 호출 스레드 상한은 Ollama와 같은 이유로 동시 질문 상한 + 2
        return new GeminiLlmClient(g.baseUrl(), g.apiKey(), g.model(), g.timeout(), chat.maxConcurrent() + 2);
    }
}
