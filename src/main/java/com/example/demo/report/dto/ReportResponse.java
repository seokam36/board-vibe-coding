package com.example.demo.report.dto;

import java.time.Instant;

import com.example.demo.report.domain.Report;

// 신고 응답. 엔티티 대신 필요한 값만 담는다.
public record ReportResponse(Long id, Target target, ReasonInfo reason, String detail, String status,
        Instant createdAt) {

    // 신고 대상. type은 POST/COMMENT, id는 그 대상의 id, postId는 대상 글의 id(글이면 id와 같다)
    public record Target(String type, Long id, Long postId) {
    }

    // 사유 {code, name}
    public record ReasonInfo(String code, String name) {
    }

    // 엔티티에서 응답을 만든다 (글/댓글 중 채워진 쪽을 본다)
    public static ReportResponse from(Report report) {
        Target target = report.getPost() != null
                ? new Target("POST", report.getPost().getId(), report.getPost().getId())
                : new Target("COMMENT", report.getComment().getId(), report.getComment().getPost().getId());
        return new ReportResponse(report.getId(), target,
                new ReasonInfo(report.getReason().name(), report.getReason().getLabel()),
                report.getDetail(), report.getStatus().name(), report.getCreatedAt());
    }
}
