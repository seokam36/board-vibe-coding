package com.example.demo.visa.domain;

// 비자 종류. 코드(enum 이름)는 DB·API에서 쓰고, label은 화면에 보여 줄 한글 이름이다.
// 값을 늘리려면 여기와 DB의 CHECK 제약(ck_visa_reviews_visa_type, 새 V 파일)을 같이 바꿔야 한다. (PostCategory와 같은 방식)
public enum VisaType {
    D_2("D-2 유학"),
    D_4("D-4 어학연수"),
    D_10("D-10 구직"),
    E_7("E-7 특정활동(취업)"),
    OTHER("기타");

    private final String label;

    VisaType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
