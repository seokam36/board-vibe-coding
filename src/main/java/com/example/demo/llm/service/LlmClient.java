package com.example.demo.llm.service;

import java.time.Duration;

// AI(LLM)에게 질문하는 창구. 서비스 코드는 이 인터페이스만 알고, 뒤에서 무엇이 답하는지(끔/Ollama/나중에 Gemini)는 모른다.
// 일반 호출(chat) + 구조화 호출(chatJson, JSON 양식) 두 가지. 도구 호출은 S17-e1 실측 결과 쓰지 않기로 했다.
public interface LlmClient {

    // 계약: systemPrompt·userMessage는 null·빈 값 금지(호출 쪽이 검증하고, 어기면 IllegalArgumentException). 빈 답은 돌려주지 않는다(LlmUnavailableException).
    // systemPrompt: AI의 역할·규칙을 알려 주는 지시문 / userMessage: 사용자가 실제로 물은 말
    // AI가 꺼져 있거나 서버에 닿지 않으면 LlmUnavailableException, 제한 시간을 넘기면 LlmTimeoutException
    String chat(String systemPrompt, String userMessage);

    // 같은 질문인데 "이번 호출 제한 시간"을 직접 정한다. 질문 하나의 전체 시간을 AI 호출 두 번이 나눠 쓸 때 쓴다.
    // timeout은 null·0·음수 금지(IllegalArgumentException). 구현의 기본 제한보다 길면 기본 제한까지만 기다린다.
    String chat(String systemPrompt, String userMessage, Duration timeout);

    // 구조화 호출: AI가 jsonSchema(JSON Schema 글자) 모양의 JSON으로만 답하게 한다. 돌려주는 건 AI가 낸 JSON 글자 그대로다.
    // JSON을 읽거나 값을 검사하는 일은 이 창구의 몫이 아니라 호출한 서비스의 몫이다(스키마도 호출한 쪽이 글자로 넘긴다).
    // 예외 규칙은 chat과 같다. schema·systemPrompt·userMessage는 빈 값 금지(schema가 JSON이 아니어도 IllegalArgumentException).
    // (a) timeout은 null·0·음수면 IllegalArgumentException, 구현의 기본 제한보다 길면 기본 제한까지만 기다린다.
    //     호출 쪽은 남은 시간이 0 이하면 이 메서드를 부르지 말고 스스로 시간 초과로 처리할 것.
    // (b) 돌려준 글자가 올바른 JSON이라거나 스키마를 지킨다는 보장은 없다. 읽고 검사하는 건 호출 쪽 몫이다.
    // (c) 빈 답이면 LlmUnavailableException.
    String chatJson(String systemPrompt, String userMessage, String jsonSchema, Duration timeout);
}
