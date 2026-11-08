package com.example.demo.report.domain;

// 신고 처리 상태. 새 신고는 PENDING(대기), 기각하면 DISMISSED.
// 값을 늘리려면 여기와 DB의 CHECK 제약(ck_reports_status, 새 V 파일)을 같이 바꿔야 한다. (ReportReason과 같은 방식)
public enum ReportStatus {
    PENDING,
    DISMISSED
}
