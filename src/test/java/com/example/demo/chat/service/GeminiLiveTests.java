package com.example.demo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.example.demo.llm.service.GeminiLlmClient;

// 진짜 제미나이에 챗봇의 진짜 양식(FORM_PROMPT·FORM_SCHEMA)과 짧은 질문을 한 번씩 보내 보는 시험.
// GEMINI_LIVE_TEST=true 이고 환경변수 GEMINI_API_KEY가 있을 때만 돈다(평소·CI에서는 건너뜀 = 무료 한도를 쓰지 않는다).
// 이 시험이 llm 패키지가 아니라 여기 있는 이유: 양식 상수는 chat.service 안에서만 보이게(package-private) 두려는 것(ChatFormLiveTests와 같음).
// 키는 환경변수에서만 읽고 출력하지 않는다.
@EnabledIfEnvironmentVariable(named = "GEMINI_LIVE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiLiveTests {

    @Test
    void 진짜_양식으로_부르면_JSON을_돌려주고_짧은_질문에는_답한다() throws Exception {
        GeminiLlmClient client = new GeminiLlmClient("https://generativelanguage.googleapis.com", System.getenv("GEMINI_API_KEY"),
                "gemini-3.5-flash-lite", Duration.ofSeconds(30), 4);

        // 1) 양식 호출(chatJson): keyword에 자전거가 들어 있어야 한다
        String json = client.chatJson(ChatService.FORM_PROMPT, "3만 원 이하 자전거 있어?", ChatService.FORM_SCHEMA, Duration.ofSeconds(30));
        System.out.println("LIVE JSON: " + json);
        var node = new tools.jackson.databind.ObjectMapper().readTree(json);
        assertThat(node.path("keyword").asText()).contains("자전거");

        // 2) 답 호출(chat): 비어 있지 않은 답
        String answer = client.chat("한국어로 한 문장으로만 짧게 답해.", "대한민국의 수도는 어디야?", Duration.ofSeconds(30));
        System.out.println("LIVE 답: " + answer);
        assertThat(answer).isNotBlank();
    }
}
