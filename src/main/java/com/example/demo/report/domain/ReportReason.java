package com.example.demo.report.domain;

// 신고 사유. 코드(enum 이름)는 DB·API에서 쓰고, label은 화면에 보여 줄 한글 이름이다.
// 값을 늘리려면 여기와 DB의 CHECK 제약(ck_reports_reason, 새 V 파일)을 같이 바꿔야 한다. (PostCategory와 같은 방식)
public enum ReportReason {
    SPAM("광고·스팸"),
    ABUSE("욕설·비방"),
    SEXUAL("음란·선정적"),
    PRIVACY("개인정보 노출"),
    OTHER("기타");

    private final String label;

    ReportReason(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
