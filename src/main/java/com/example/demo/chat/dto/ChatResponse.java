package com.example.demo.chat.dto;

import java.util.List;

// 챗봇 응답: AI의 답과, 그 답의 근거로 AI에게 준 글 목록(출처). 맞는 글이 없으면 sources는 [].
public record ChatResponse(String answer, List<ChatSource> sources) {
}
