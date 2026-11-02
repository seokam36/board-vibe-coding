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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 게시글 한 건을 나타내는 엔티티 (posts 테이블)
@Entity
@Table(name = "posts")
@Getter // getter 자동 생성
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 protected 기본 생성자 자동 생성 (외부에서는 쓰지 않음)
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

    // 새 게시글은 제목과 본문으로만 만든다 (id, createdAt은 저장 시 채워짐)
    public Post(String title, String content) {
        this.title = title;
        this.content = content;
    }

    // 제목과 본문만 바꾼다 (setter 대신 의도가 드러나는 메서드. createdAt은 updatable=false라 그대로 유지됨)
    public void update(String title, String content) {
        this.title = title;
        this.content = content;
    }

    // 저장 직전에 작성 시각을 자동 기록 (별도 라이브러리 없이 JPA 표준만 사용)
    @PrePersist
    void onCreate() {
        // Instant(UTC)로 시간대 혼선을 없애고, DB(마이크로초)와 같은 정밀도로 잘라 POST/GET 응답 시각을 일치시킨다
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
