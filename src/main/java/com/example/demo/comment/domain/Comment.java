package com.example.demo.comment.domain;

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

// 댓글 한 건을 나타내는 엔티티 (comments 테이블)
@Entity
@Table(name = "comments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 댓글이 달린 글. 글이 지워지면 DB(ON DELETE CASCADE)가 댓글도 지운다. 그래서 Post 쪽에 cascade 설정은 없다.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false, updatable = false)
    private Post post;

    // 댓글쓴이. 회원이 탈퇴하면 null로 비워지므로 nullable.
    // updatable = false: 수정 때 dirty checking UPDATE가 author_id를 옛 값으로 다시 쓰면, 수정 중 탈퇴와 겹칠 때
    // FK 위반(500)이 나기 때문이다. (Post.author와 같은 이유. 탈퇴 때는 JPQL 일괄 UPDATE로 비운다)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", updatable = false)
    private User author;

    // varchar(1000)이라 length를 맞춘다 (TEXT로 매핑하면 ddl-auto=validate에서 앱이 안 뜬다)
    @Column(nullable = false, length = 1000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public Comment(Post post, User author, String content) {
        this.post = post;
        this.author = author;
        this.content = content;
    }

    // 내용만 바꾼다 (setter 대신 의도가 드러나는 메서드)
    public void update(String content) {
        this.content = content;
    }

    // userId가 쓴 댓글인지 확인. 작성자가 없는(탈퇴한) 댓글은 누구의 것도 아니므로 false.
    public boolean isWrittenBy(Long userId) {
        return author != null && author.getId().equals(userId);
    }

    // 저장 직전에 작성 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
