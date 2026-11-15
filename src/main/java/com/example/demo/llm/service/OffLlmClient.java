package com.example.demo.llm.service;

import java.time.Duration;

// LLM_MODE=off(기본)일 때의 구현. AI가 꺼져 있으니 호출하면 항상 "사용 불가"로 답한다.
public class OffLlmClient implements LlmClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    // 인자 검사는 하지 않는다: 꺼져 있으면 인자와 무관하게 항상 "사용 불가"다(인자 검사는 켜진 구현의 몫).
    @Override
    public String chat(String systemPrompt, String userMessage) {
        throw new LlmUnavailableException("AI 기능이 꺼져 있습니다 (LLM_MODE=off).");
    }

    @Override
    public String chat(String systemPrompt, String userMessage, Duration timeout) {
        throw new LlmUnavailableException("AI 기능이 꺼져 있습니다 (LLM_MODE=off).");
    }

    @Override
    public String chatJson(String systemPrompt, String userMessage, String jsonSchema, Duration timeout) {
        throw new LlmUnavailableException("AI 기능이 꺼져 있습니다 (LLM_MODE=off).");
    }
}
