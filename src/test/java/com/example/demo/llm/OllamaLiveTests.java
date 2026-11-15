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
        String answer = client.chat("한국어로 한 문장으로만 짧게 답해.", "대한민국의 수도는 어디야?", Duration.ofSeconds(60));
        System.out.println("LIVE 답: " + answer);
        assertThat(answer).isNotBlank();
    }

    // 구조화 호출: format(JSON Schema)을 줬을 때 실제 모델이 양식대로 JSON을 채우는지
    @Test
    void 구조화_호출은_JSON을_돌려주고_keyword에_자전거가_들어_있다() throws Exception {
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:11434", "qwen3:8b", Duration.ofSeconds(60));
        // llm 패키지는 chat을 모르는 아래 계층이라 예시 양식(keyword, maxPrice)을 직접 쓴다. 챗봇의 진짜 양식은 chat.service.ChatFormLiveTests가 시험한다.
        String schema = "{\"type\":\"object\",\"properties\":{\"keyword\":{\"type\":\"string\"},\"maxPrice\":{\"type\":\"integer\"}}}";
        String json = client.chatJson("사용자의 말에서 중고 물품 검색 조건을 JSON 양식에 채워라. 가격은 원 단위 정수.",
                "3만 원 이하 자전거 있어?", schema, Duration.ofSeconds(60));
        System.out.println("LIVE JSON: " + json);
        var node = new tools.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.path("keyword").asText()).contains("자전거");
    }
}
