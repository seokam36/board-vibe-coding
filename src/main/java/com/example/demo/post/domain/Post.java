package com.example.demo.post.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

// 게시글 한 건을 나타내는 엔티티 (posts 테이블)
@Entity
@Table(name = "posts")
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    // 길이 제한 없는 본문이라 TEXT 타입 사용
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // JPA가 쓰는 기본 생성자 (외부에서는 쓰지 않음)
    protected Post() {
    }

    // 새 게시글은 제목과 본문으로만 만든다 (id, createdAt은 저장 시 채워짐)
    public Post(String title, String content) {
        this.title = title;
        this.content = content;
    }

    // 저장 직전에 작성 시각을 자동 기록 (별도 라이브러리 없이 JPA 표준만 사용)
    @PrePersist
    void onCreate() {
        // Instant(UTC)로 시간대 혼선을 없애고, DB(마이크로초)와 같은 정밀도로 잘라 POST/GET 응답 시각을 일치시킨다
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
