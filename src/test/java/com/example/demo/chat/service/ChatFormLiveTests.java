package com.example.demo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.example.demo.llm.service.OllamaLlmClient;

// 진짜 Ollama에 챗봇의 진짜 양식(ChatService.FORM_PROMPT·FORM_SCHEMA)을 보내 보는 시험. OLLAMA_LIVE_TEST=true일 때만 돈다.
// 이 시험이 llm 패키지(OllamaLiveTests)가 아니라 여기 있는 이유: 양식 상수는 chat.service 안에서만 보이게(package-private) 두고,
// llm 패키지가 chat 패키지를 import하지 않게(llm은 chat을 모르는 아래 계층) 하려는 것이다.
@EnabledIfEnvironmentVariable(named = "OLLAMA_LIVE_TEST", matches = "true")
class ChatFormLiveTests {

    @Test
    void 진짜_양식으로_부르면_JSON을_돌려주고_keyword에_자전거가_들어_있다() throws Exception {
        OllamaLlmClient client = new OllamaLlmClient("http://localhost:11434", "qwen3:8b", Duration.ofSeconds(60));
        String json = client.chatJson(ChatService.FORM_PROMPT, "3만 원 이하 자전거 있어?", ChatService.FORM_SCHEMA, Duration.ofSeconds(60));
        System.out.println("LIVE JSON: " + json);
        var node = new tools.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.path("keyword").asText()).contains("자전거");
    }
}
