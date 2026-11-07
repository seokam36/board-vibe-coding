package com.example.demo.post.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.demo.school.domain.School;
import com.example.demo.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    // 글쓴이. S4-d 이전에 쓴 글은 작성자가 없어서 nullable(null 허용)이다.
    // LAZY: 글만 읽을 때는 사용자 테이블을 같이 읽지 않는다. (목록·상세는 리포지토리에서 한 번에 가져온다)
    @ManyToOne(fetch = FetchType.LAZY)
    // updatable = false: 작성자는 엔티티로 바꾸지 않는다. 글 수정 때 dirty checking UPDATE가 author_id를 옛 값으로 다시 쓰면,
    // 수정 중 탈퇴(author_id가 null로 바뀜)와 겹칠 때 FK 위반(500)이 나기 때문이다. (탈퇴 때는 JPQL 일괄 UPDATE로 비운다)
    @JoinColumn(name = "author_id", updatable = false)
    private User author;

    // 글이 속한 학교. 옛 글은 학교가 없어서 nullable. 새 글은 서비스가 항상 채운다.
    // updatable = false: 학교는 작성 후 바꿀 수 없다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "school_id", updatable = false)
    private School school;

    // 말머리. enum 이름(CHAT 등)을 문자열로 저장한다(STRING). 순서 번호로 저장하면 enum 순서가 바뀔 때 데이터가 꼬인다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostCategory category;

    // 새 게시글은 제목·본문·작성자·학교·말머리로 만든다 (id, createdAt은 저장 시 채워짐)
    public Post(String title, String content, User author, School school, PostCategory category) {
        this.title = title;
        this.content = content;
        this.author = author;
        this.school = school;
        this.category = category;
    }

    // 제목·본문·말머리를 바꾼다 (setter 대신 의도가 드러나는 메서드. 학교·작성 시각은 바뀌지 않는다)
    public void update(String title, String content, PostCategory category) {
        this.title = title;
        this.content = content;
        this.category = category;
    }

    // 이 글을 userId가 쓴 게 맞는지 확인한다. 작성자가 없는 옛 글은 누구의 글도 아니므로 false.
    // author.getId()는 프록시여도 DB를 다시 읽지 않는다.
    public boolean isWrittenBy(Long userId) {
        return author != null && author.getId().equals(userId);
    }

    // 저장 직전에 작성 시각을 자동 기록 (별도 라이브러리 없이 JPA 표준만 사용)
    @PrePersist
    void onCreate() {
        // Instant(UTC)로 시간대 혼선을 없애고, DB(마이크로초)와 같은 정밀도로 잘라 POST/GET 응답 시각을 일치시킨다
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
