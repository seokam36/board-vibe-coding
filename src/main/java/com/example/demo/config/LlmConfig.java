package com.example.demo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.llm.service.LlmClient;
import com.example.demo.llm.service.OffLlmClient;
import com.example.demo.llm.service.OllamaLlmClient;

// LLM_MODE 설정으로 AI 구현을 갈아 끼운다: off(기본) = 꺼짐, ollama = 내 PC의 Ollama.
// 이 클래스의 생성자가 시작 때 모드를 검사한다. 이상한 값이면 허용 값을 알려 주며 시작이 실패한다(값 자체는 출력 안 함).
// Ollama 서버가 꺼져 있어도 시작은 성공한다(연결은 질문할 때 처음 한다).
@Configuration
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    public LlmConfig(LlmProperties props) {
        String mode = props.mode();
        if (!"off".equals(mode) && !"ollama".equals(mode)) {
            throw new IllegalStateException("LLM_MODE는 off 또는 ollama여야 합니다 (현재 값은 출력하지 않음).");
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
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.mode", havingValue = "off", matchIfMissing = true)
    LlmClient offLlmClient() {
        return new OffLlmClient();
    }

    @Bean
    @ConditionalOnProperty(name = "app.llm.mode", havingValue = "ollama")
    LlmClient ollamaLlmClient(LlmProperties props) {
        LlmProperties.Ollama o = props.ollama();
        return new OllamaLlmClient(o.baseUrl(), o.model(), o.timeout());
    }
}
