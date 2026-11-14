package com.example.demo.llm.service;

// AI(LLM)에게 질문하는 창구. 서비스 코드는 이 인터페이스만 알고, 뒤에서 무엇이 답하는지(끔/Ollama/나중에 Gemini)는 모른다.
// 지금은 "지시문 + 사용자 메시지 -> 답 문자열"만 둔다. 도구 호출(S17-e)이 필요해지면 그때 넓힌다.
public interface LlmClient {

    // 계약: systemPrompt·userMessage는 null·빈 값 금지(호출 쪽이 검증하고, 어기면 IllegalArgumentException). 빈 답은 돌려주지 않는다(LlmUnavailableException).
    // systemPrompt: AI의 역할·규칙을 알려 주는 지시문 / userMessage: 사용자가 실제로 물은 말
    // AI가 꺼져 있거나 서버에 닿지 않으면 LlmUnavailableException, 제한 시간을 넘기면 LlmTimeoutException
    String chat(String systemPrompt, String userMessage);
}
