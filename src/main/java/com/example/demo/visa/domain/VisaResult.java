package com.example.demo.visa.domain;

// 비자 심사 결과. 코드(enum 이름)는 DB·API에서 쓰고, label은 화면에 보여 줄 한글 이름이다.
// 값을 늘리려면 여기와 DB의 CHECK 제약(ck_visa_reviews_result, 새 V 파일)을 같이 바꿔야 한다.
public enum VisaResult {
    APPROVED("승인"),
    DENIED("불허");

    private final String label;

    VisaResult(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
