package com.example.demo.report.domain;

// 신고 처리 상태. 새 신고는 PENDING(대기), 기각하면 DISMISSED.
public enum ReportStatus {
    PENDING,
    DISMISSED
}
