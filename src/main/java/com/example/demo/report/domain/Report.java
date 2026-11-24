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

    // 신고 출처. USER = 회원 신고(기본), AUTO = 사진 자동 검사 신고(reporter 없음). DB의 CHECK가 AUTO의 모양을 보장한다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private ReportSource source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    private Report(Post post, Comment comment, MarketItem marketItem, Conversation conversation, User reporter,
            ReportReason reason, String detail, ReportSource source) {
        this.post = post;
        this.comment = comment;
        this.marketItem = marketItem;
        this.conversation = conversation;
        this.reporter = reporter;
        this.reason = reason;
        this.detail = detail;
        this.source = source;
        this.status = ReportStatus.PENDING; // 새 신고는 항상 대기 상태
    }

    // 글 신고 만들기
    public static Report ofPost(Post post, User reporter, ReportReason reason, String detail) {
        return new Report(post, null, null, null, reporter, reason, detail, ReportSource.USER);
    }

    // 댓글 신고 만들기
    public static Report ofComment(Comment comment, User reporter, ReportReason reason, String detail) {
        return new Report(null, comment, null, null, reporter, reason, detail, ReportSource.USER);
    }

    // 중고 글 신고 만들기
    public static Report ofMarketItem(MarketItem marketItem, User reporter, ReportReason reason, String detail) {
        return new Report(null, null, marketItem, null, reporter, reason, detail, ReportSource.USER);
    }

    // 사진 자동 검사가 만드는 중고 글 신고 (S30-b). 신고자 없음(reporter null), 출처 AUTO.
    public static Report ofMarketItemAuto(MarketItem marketItem, ReportReason reason, String detail) {
        return new Report(null, null, marketItem, null, null, reason, detail, ReportSource.AUTO);
    }

    // 자동 신고의 설명에 사진 정보를 덧붙인다 (AUTO만 허용). 글 행을 잠근 트랜잭션 안에서만 부른다.
    // 총 길이가 maxLength(DB 제한 300)를 넘을 것 같으면 더 붙이지 않고 끝에 " 외"만 표시한다.
    // (붙이기를 받아들이는 기준에 " 외" 두 글자를 미리 빼 두므로, 나중에 " 외"를 붙일 자리는 항상 남는다)
    public void appendAutoDetail(String entry, int maxLength) {
        if (source != ReportSource.AUTO) {
            throw new IllegalStateException("자동 신고에만 설명을 덧붙일 수 있습니다.");
        }
        String current = detail == null ? "" : detail;
        if (current.endsWith(" 외")) {
            return; // 이미 넘쳐서 줄임 표시를 한 상태
        }
        String next = current + " / " + entry;
        if (next.length() + 2 <= maxLength) {
            this.detail = next;
        } else if (current.length() + 2 <= maxLength) {
            this.detail = current + " 외";
        }
    }

    // 쪽지 대화방 신고 만들기
    public static Report ofConversation(Conversation conversation, User reporter, ReportReason reason, String detail) {
        return new Report(null, null, null, conversation, reporter, reason, detail, ReportSource.USER);
    }

    // 저장 직전에 신고 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
