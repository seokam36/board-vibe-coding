package com.example.demo.message.dto;

// 안 읽은 쪽지 수 (GET /conversations/unread-count)
public record UnreadCountResponse(long count) {
}
