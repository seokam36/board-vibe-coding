package com.example.demo.report.domain;

// 신고가 어디서 왔나. USER = 회원이 직접 한 신고, AUTO = 사진 자동 검사가 애매하다고 올린 신고(신고자 없음).
// 값을 늘리려면 DB의 CHECK 제약(ck_reports_source, 새 V 파일)도 같이 바꾼다.
public enum ReportSource {
    USER, AUTO
}
