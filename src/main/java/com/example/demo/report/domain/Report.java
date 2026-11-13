package com.example.demo.report.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.demo.comment.domain.Comment;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.message.domain.Conversation;
import com.example.demo.post.domain.Post;
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

// 신고 한 건 (reports 테이블). 대상은 글·댓글·중고 글·쪽지 대화방 중 하나만 채운다(DB의 CHECK가 보장).
// 대상이 지워지면 DB(ON DELETE CASCADE)가, 신고자가 탈퇴하면 DB(ON DELETE SET NULL)가 처리하므로 엔티티 쪽 cascade 설정은 없다.
@Entity
@Table(name = "reports")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 신고된 글 (댓글 신고면 null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "post_id", updatable = false)
    private Post post;

    // 신고된 댓글 (글 신고면 null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comment_id", updatable = false)
    private Comment comment;

    // 신고된 중고 글 (글/댓글 신고면 null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "market_item_id", updatable = false)
    private MarketItem marketItem;

    // 신고된 쪽지 대화방 (그 밖의 신고면 null)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", updatable = false)
    private Conversation conversation;

    // 신고자. 탈퇴하면 DB가 null로 비우므로 nullable. updatable = false: 수정 때 옛 값을 다시 써서 FK 위반이 나는 걸 막는다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reporter_id", updatable = false)
    private User reporter;

    // enum 이름을 문자열로 저장한다(STRING). 순서 번호로 저장하면 enum 순서가 바뀔 때 데이터가 꼬인다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportReason reason;

    // 선택 입력(최대 300자). 없으면 null
    @Column(length = 300)
    private String detail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Report(Post post, Comment comment, MarketItem marketItem, Conversation conversation, User reporter,
            ReportReason reason, String detail) {
        this.post = post;
        this.comment = comment;
        this.marketItem = marketItem;
        this.conversation = conversation;
        this.reporter = reporter;
        this.reason = reason;
        this.detail = detail;
        this.status = ReportStatus.PENDING; // 새 신고는 항상 대기 상태
    }

    // 글 신고 만들기
    public static Report ofPost(Post post, User reporter, ReportReason reason, String detail) {
        return new Report(post, null, null, null, reporter, reason, detail);
    }

    // 댓글 신고 만들기
    public static Report ofComment(Comment comment, User reporter, ReportReason reason, String detail) {
        return new Report(null, comment, null, null, reporter, reason, detail);
    }

    // 중고 글 신고 만들기
    public static Report ofMarketItem(MarketItem marketItem, User reporter, ReportReason reason, String detail) {
        return new Report(null, null, marketItem, null, reporter, reason, detail);
    }

    // 쪽지 대화방 신고 만들기
    public static Report ofConversation(Conversation conversation, User reporter, ReportReason reason, String detail) {
        return new Report(null, null, null, conversation, reporter, reason, detail);
    }

    // 저장 직전에 신고 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
