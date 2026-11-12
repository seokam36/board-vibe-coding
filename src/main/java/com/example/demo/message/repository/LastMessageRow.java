package com.example.demo.message.repository;

import java.time.Instant;

// 대화 목록용 "방의 마지막 쪽지" 조회 결과 한 줄(엔티티를 읽지 않고 필요한 칸만 가져온다).
// senderId는 보낸 사람이 탈퇴했으면 null.
public record LastMessageRow(Long conversationId, String content, Instant createdAt, Long senderId) {
}
