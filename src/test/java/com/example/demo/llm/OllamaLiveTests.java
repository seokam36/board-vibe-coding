package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.example.demo.llm.service.OllamaLlmClient;

// 진짜 Ollama를 부르는 시험. OLLAMA_LIVE_TEST=true일 때만 돌고, 아니면 건너뜀(skipped)으로 표시된다. 주소·모델은 여기서 직접 정한다.
@EnabledIfEnvironmentVariable(named = "OLLAMA_LIVE_TEST", matches = "true")
class OllamaLiveTests {

    @Test
    void qwen3에_짧게_물으면_빈_문자열이_아닌_답이_온다() {
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:11434", "qwen3:8b", Duration.ofSeconds(60));
        String answer = client.chat("한국어로 한 문장으로만 짧게 답해.", "대한민국의 수도는 어디야?");
        System.out.println("LIVE 답: " + answer);
        assertThat(answer).isNotBlank();
    }
}
