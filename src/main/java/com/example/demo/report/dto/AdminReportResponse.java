package com.example.demo.report.dto;

import java.time.Instant;

import com.example.demo.comment.domain.Comment;
import com.example.demo.post.domain.Post;
import com.example.demo.report.domain.Report;
import com.example.demo.user.domain.User;
import com.example.demo.user.dto.AuthorResponse;

// 관리자 신고 목록·기각 응답. 관리자가 "무엇이 왜 신고됐는지" 바로 볼 수 있게 대상 미리보기와 작성자를 담는다.
public record AdminReportResponse(Long id, Target target, AuthorResponse reporter, ReportResponse.ReasonInfo reason,
        String detail, String status, Instant createdAt) {

    // 댓글 미리보기 최대 글자 수
    static final int PREVIEW_MAX = 100;

    // 신고 대상. preview = 글이면 제목, 댓글이면 내용 앞 100자(넘으면 "…"을 붙인다).
    // author = 대상 작성자(탈퇴했거나 작성자 없는 옛 글이면 null)
    public record Target(String type, Long id, Long postId, String preview, AuthorResponse author) {
    }

    // 엔티티에서 응답을 만든다. 글·댓글·신고자·작성자는 저장소가 미리 같이 읽어 와서 여기서 추가 쿼리가 나가지 않는다.
    public static AdminReportResponse from(Report report) {
        Target target;
        Post post = report.getPost();
        if (post != null) {
            target = new Target("POST", post.getId(), post.getId(), post.getTitle(), author(post.getAuthor()));
        } else {
            Comment comment = report.getComment();
            target = new Target("COMMENT", comment.getId(), comment.getPost().getId(), preview(comment.getContent()),
                    author(comment.getAuthor()));
        }
        return new AdminReportResponse(report.getId(), target, author(report.getReporter()),
                new ReportResponse.ReasonInfo(report.getReason().name(), report.getReason().getLabel()),
                report.getDetail(), report.getStatus().name(), report.getCreatedAt());
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
