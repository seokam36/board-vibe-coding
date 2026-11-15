package com.example.demo.llm.service;

// AI를 쓸 수 없을 때(꺼져 있음, Ollama 서버가 안 켜져 있음, 구글 제미나이가 오류로 답함 등). 챗봇이 화면용 오류(503)로 바꾼다.
// 주의: cause(원본 예외) 메시지에 AI 주소·구글 오류 본문이 들어 있을 수 있으니 응답 본문·로그에 그대로 넣지 말 것.
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
