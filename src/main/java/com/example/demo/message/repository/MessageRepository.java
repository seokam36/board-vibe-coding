package com.example.demo.message.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.message.domain.Message;

// 쪽지 DB 접근 담당 (S14-a는 저장만 쓴다. 목록 조회는 S14-b에서 추가)
public interface MessageRepository extends JpaRepository<Message, Long> {
}
