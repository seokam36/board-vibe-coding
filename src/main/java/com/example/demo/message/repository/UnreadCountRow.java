package com.example.demo.message.repository;

// 대화 목록용 "방별 안 읽은 쪽지 수" 조회 결과 한 줄. 안 읽은 게 없는 방은 이 줄 자체가 없다(서비스가 0으로 채운다).
public record UnreadCountRow(Long conversationId, long count) {
}
