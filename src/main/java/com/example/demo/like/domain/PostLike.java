package com.example.demo.like.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.demo.post.domain.Post;
import com.example.demo.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 공감 한 건 (post_likes 테이블). 한 회원이 한 글에 한 번만 누르는 건 DB의 UNIQUE(post_id, user_id)가 보장한다.
// 글이 지워지거나 회원이 탈퇴하면 DB(ON DELETE CASCADE)가 같이 지우므로 엔티티 쪽 cascade 설정은 없다.
@Entity
@Table(name = "post_likes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class PostLike {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, updatable = false)
    private Post post;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PostLike(Post post, User user) {
        this.post = post;
        this.user = user;
    }

    // 저장 직전에 누른 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
