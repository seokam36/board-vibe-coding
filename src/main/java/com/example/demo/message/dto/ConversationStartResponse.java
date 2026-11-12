package com.example.demo.message.dto;

// 첫 쪽지 보내기 응답: 대화방 번호 + 방금 보낸 쪽지
public record ConversationStartResponse(Long conversationId, MessageResponse message) {
}
