package com.example.demo.report.dto;

import java.time.Instant;

import com.example.demo.comment.domain.Comment;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.message.domain.Conversation;
import com.example.demo.post.domain.Post;
import com.example.demo.report.domain.Report;
import com.example.demo.user.domain.User;
import com.example.demo.user.dto.AuthorResponse;

// 관리자 신고 목록·기각 응답. 관리자가 "무엇이 왜 신고됐는지" 바로 볼 수 있게 대상 미리보기와 작성자를 담는다.
public record AdminReportResponse(Long id, Target target, AuthorResponse reporter, ReportResponse.ReasonInfo reason,
        String detail, String status, Instant createdAt) {

    // 댓글 미리보기 최대 글자 수
    static final int PREVIEW_MAX = 100;

    // 신고 대상. preview = 글·중고 글이면 제목, 댓글이면 내용 앞 100자(넘으면 "…"을 붙인다).
    // 대화방이면 id = 방 id, preview = 방을 만들 때 복사해 둔 글 제목.
    // postId = 중고 글·대화방이면 null.
    // author = 대상 작성자(중고 글이면 판매자, 대화방이면 신고자가 아닌 참가자. 탈퇴했거나 작성자 없는 옛 글이면 null)
    public record Target(String type, Long id, Long postId, String preview, AuthorResponse author) {
    }

    // 엔티티에서 응답을 만든다. 글·댓글·신고자·작성자는 저장소가 미리 같이 읽어 와서 여기서 추가 쿼리가 나가지 않는다.
    public static AdminReportResponse from(Report report) {
        Target target;
        Post post = report.getPost();
        if (post != null) {
            target = new Target("POST", post.getId(), post.getId(), post.getTitle(), author(post.getAuthor()));
        } else if (report.getMarketItem() != null) {
            MarketItem item = report.getMarketItem();
            target = new Target("MARKET_ITEM", item.getId(), null, item.getTitle(), author(item.getSeller()));
        } else if (report.getConversation() != null) {
            Conversation room = report.getConversation();
            target = new Target("CONVERSATION", room.getId(), null, room.getItemTitle(),
                    author(otherParticipant(room, report.getReporter())));
        } else if (report.getComment() != null) {
            Comment comment = report.getComment();
            target = new Target("COMMENT", comment.getId(), comment.getPost().getId(), preview(comment.getContent()),
                    author(comment.getAuthor()));
        } else {
            // DB의 CHECK(ck_reports_target)가 막으므로 오지 않는 상태. 대상 종류가 또 늘었는데 여기를 빠뜨리면 바로 알 수 있게 한다.
            throw new IllegalStateException("신고 대상이 없는 신고입니다. id=" + report.getId());
        }
        return new AdminReportResponse(report.getId(), target, author(report.getReporter()),
                new ReportResponse.ReasonInfo(report.getReason().name(), report.getReason().getLabel()),
                report.getDetail(), report.getStatus().name(), report.getCreatedAt());
    }

    // 대화방에서 신고자가 아닌 참가자. 탈퇴한 칸은 null이라 상대가 탈퇴했으면 null.
    // 신고자가 탈퇴했으면(reporter == null) 그 사람의 방 칸도 같이 비워졌으므로 남아 있는 참가자가 상대다.
    private static User otherParticipant(Conversation room, User reporter) {
        for (User participant : new User[] { room.getBuyer(), room.getSeller() }) {
            if (participant != null && (reporter == null || !participant.getId().equals(reporter.getId()))) {
                return participant;
            }
        }
        return null;
    }

    private static AuthorResponse author(User user) {
        return user == null ? null : AuthorResponse.from(user);
    }

    // 앞 100글자(이모지 같은 2칸짜리 문자를 반으로 자르지 않도록 코드포인트 기준)만 남기고, 잘렸으면 "…"을 붙인다
    static String preview(String content) {
        if (content.codePointCount(0, content.length()) <= PREVIEW_MAX) {
            return content;
        }
        return content.substring(0, content.offsetByCodePoints(0, PREVIEW_MAX)) + "…";
    }
}
