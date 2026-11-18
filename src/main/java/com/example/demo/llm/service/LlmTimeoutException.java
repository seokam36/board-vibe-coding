package com.example.demo.llm.service;

// AI가 제한 시간 안에 답하지 못했을 때. 챗봇(ChatService)이 받아 화면용 504로 바꾼다.
public class LlmTimeoutException extends RuntimeException {

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
