package com.example.demo.visa.domain;

// 비자 신청 종류. 코드(enum 이름)는 DB·API에서 쓰고, label은 화면에 보여 줄 한글 이름이다.
// 값을 늘리려면 여기와 DB의 CHECK 제약(ck_visa_reviews_application_type, 새 V 파일)을 같이 바꿔야 한다.
public enum VisaApplicationType {
    NEW("신규 발급"),
    REGISTRATION("외국인등록"),
    EXTENSION("체류기간 연장"),
    CHANGE("체류자격 변경"),
    OTHER("기타");

    private final String label;

    VisaApplicationType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
