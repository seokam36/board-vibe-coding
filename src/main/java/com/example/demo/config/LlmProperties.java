package com.example.demo.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

// application.properties의 app.llm.* 설정.
// mode: off(AI 끔) / ollama(내 PC의 Ollama) / gemini(구글 제미나이 API)
// ollama·gemini 안의 timeout = 질문 하나 전체 예산 겸 호출 하나의 상한
@ConfigurationProperties("app.llm")
public record LlmProperties(
        @DefaultValue("off") String mode,
        @DefaultValue Ollama ollama,
        @DefaultValue Gemini gemini) {

    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("qwen3:8b") String model,
            // 단위 없는 숫자(60)는 초로 읽는다
            @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) Duration timeout) {
    }

    public record Gemini(
            // 전용 환경변수(GEMINI_BASE_URL)는 일부러 만들지 않았다. 스프링 규칙상 app.llm.gemini.base-url 속성이나
            // APP_LLM_GEMINI_BASEURL·APP_LLM_GEMINI_BASE_URL 환경변수로는 바꿀 수 있어서, LlmConfig가 https(내 컴퓨터만 http)만 허용한다.
            @DefaultValue("https://generativelanguage.googleapis.com") String baseUrl,
            // 비밀 값. 기본은 빈 값이라 gemini 모드인데 키가 없으면 LlmConfig가 시작 때 막는다.
            @DefaultValue("") String apiKey,
            @DefaultValue("gemini-3.5-flash-lite") String model,
            @DefaultValue("30s") @DurationUnit(ChronoUnit.SECONDS) Duration timeout) {

        // record의 기본 toString은 모든 값을 찍어서 키가 로그·화면에 나올 수 있다. 키만 가려서 찍는다.
        @Override
        public String toString() {
            return "Gemini[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isEmpty() ? "(없음)" : "****")
                    + ", model=" + model + ", timeout=" + timeout + "]";
        }
    }

    // 지금 모드의 "질문 하나 전체" 시간 예산. gemini는 GEMINI_TIMEOUT, ollama는 OLLAMA_TIMEOUT.
    // off도 ollama 값을 돌려준다. 운영 off는 ask() 맨 앞 503이라 안 쓰이지만,
    // 테스트는 off 모드 + 가짜 AI(ScriptedLlmClient)로 이 값을 예산으로 쓴다(ChatTimeBudgetConfigTests).
    // 모르는 모드에 조용히 ollama 값을 주지 않도록 세 값만 명시한다(LlmConfig가 시작 때 이미 세 값만 허용해서 실제로는 닿지 않는다).
    public Duration budget() {
        return switch (mode) {
            case "gemini" -> gemini.timeout();
            case "ollama", "off" -> ollama.timeout();
            // 현재 값은 출력하지 않는다(LlmConfig와 같은 원칙)
            default -> throw new IllegalStateException("알 수 없는 app.llm.mode (off, ollama, gemini만 가능)");
        };
    }

    // 위 예산을 정하는 환경변수 이름(오류 메시지용). 모드별 규칙은 budget()과 같다
    public String budgetVariable() {
        return switch (mode) {
            case "gemini" -> "GEMINI_TIMEOUT";
            case "ollama", "off" -> "OLLAMA_TIMEOUT";
            default -> throw new IllegalStateException("알 수 없는 app.llm.mode (off, ollama, gemini만 가능)");
        };
    }
}
