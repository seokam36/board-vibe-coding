package com.example.demo.report.dto;

import java.time.Instant;

import com.example.demo.report.domain.Report;

// 신고 응답. 엔티티 대신 필요한 값만 담는다.
public record ReportResponse(Long id, Target target, ReasonInfo reason, String detail, String status,
        Instant createdAt) {

    // 신고 대상. type은 POST/COMMENT/MARKET_ITEM/CONVERSATION, id는 그 대상의 id,
    // postId는 대상 글의 id(글이면 id와 같다, 중고 글·대화방이면 null)
    public record Target(String type, Long id, Long postId) {
    }

    // 사유 {code, name}
    public record ReasonInfo(String code, String name) {
    }

    // 엔티티에서 응답을 만든다 (글/중고 글/대화방/댓글 중 채워진 쪽을 본다)
    public static ReportResponse from(Report report) {
        Target target;
        if (report.getPost() != null) {
            target = new Target("POST", report.getPost().getId(), report.getPost().getId());
        } else if (report.getMarketItem() != null) {
            target = new Target("MARKET_ITEM", report.getMarketItem().getId(), null);
        } else if (report.getConversation() != null) {
            target = new Target("CONVERSATION", report.getConversation().getId(), null);
        } else if (report.getComment() != null) {
            target = new Target("COMMENT", report.getComment().getId(), report.getComment().getPost().getId());
        } else {
            // DB의 CHECK(ck_reports_target)가 막으므로 오지 않는 상태. 대상 종류가 또 늘었는데 여기를 빠뜨리면 바로 알 수 있게 한다.
            throw new IllegalStateException("신고 대상이 없는 신고입니다. id=" + report.getId());
        }
        return new ReportResponse(report.getId(), target,
                new ReasonInfo(report.getReason().name(), report.getReason().getLabel()),
                report.getDetail(), report.getStatus().name(), report.getCreatedAt());
    }
}
