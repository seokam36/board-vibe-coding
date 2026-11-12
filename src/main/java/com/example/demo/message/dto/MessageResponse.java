package com.example.demo.message.dto;

import java.time.Instant;

import com.example.demo.message.domain.Message;

// 쪽지 한 통의 응답. mine = 내가 보낸 쪽지인가(화면이 왼쪽/오른쪽을 정하는 데 쓴다).
public record MessageResponse(Long id, String content, Instant createdAt, boolean mine) {

    public static MessageResponse of(Message message, boolean mine) {
        return new MessageResponse(message.getId(), message.getContent(), message.getCreatedAt(), mine);
    }
}
