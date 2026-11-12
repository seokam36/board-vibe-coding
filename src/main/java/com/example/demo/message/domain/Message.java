package com.example.demo.message.domain;

import java.time.Instant;

import com.example.demo.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 쪽지 한 통 (messages 표). 수정·삭제는 없다. 방이 지워지면 DB가 같이 지운다(CASCADE).
@Entity
@Table(name = "messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "conversation_id", nullable = false, updatable = false)
    private Conversation conversation;

    // 보낸 사람. 탈퇴하면 null
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_id", updatable = false)
    private User sender;

    @Column(nullable = false, length = 1000, updatable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 읽은 시각. 읽음 처리(MessageRepository.markRead)의 UPDATE 한 문장으로만 채운다
    @Column(name = "read_at")
    private Instant readAt;

    // 시각은 서비스가 정해서 넘긴다(방의 마지막 쪽지 시각과 똑같은 값을 쓰려고)
    public Message(Conversation conversation, User sender, String content, Instant createdAt) {
        this.conversation = conversation;
        this.sender = sender;
        this.content = content;
        this.createdAt = createdAt;
    }
}
