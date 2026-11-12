package com.example.demo.message.dto;

import java.time.Instant;

// 대화 목록에 보이는 마지막 쪽지 한 줄. mine = 내가 보낸 쪽지인가(보낸 사람이 탈퇴해 비었으면 false).
public record LastMessageInfo(String content, Instant createdAt, boolean mine) {
}
