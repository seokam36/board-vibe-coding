package com.example.demo.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.convert.DurationUnit;

// application.properties의 app.llm.* 설정. mode: off(AI 끔) 또는 ollama(내 PC의 Ollama) / ollama: 주소·모델·호출 한 번의 제한 시간
@ConfigurationProperties("app.llm")
public record LlmProperties(@DefaultValue("off") String mode, @DefaultValue Ollama ollama) {

    public record Ollama(
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("qwen3:8b") String model,
            // 단위 없는 숫자(60)는 초로 읽는다
            @DefaultValue("60s") @DurationUnit(ChronoUnit.SECONDS) Duration timeout) {
    }
}
