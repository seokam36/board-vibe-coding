package com.example.demo.llm.service;

// AI가 제한 시간 안에 답하지 못했을 때. 나중에 화면용 오류(504)로 바꾼다.
public class LlmTimeoutException extends RuntimeException {

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
