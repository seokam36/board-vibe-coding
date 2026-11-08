package com.example.demo.report.dto;

import com.example.demo.report.domain.ReportReason;

// 신고 사유 응답 {code, name}. code는 API에 보내는 값, name은 화면용 한글 이름.
public record ReportReasonResponse(String code, String name) {

    public static ReportReasonResponse from(ReportReason reason) {
        return new ReportReasonResponse(reason.name(), reason.getLabel());
    }
}
