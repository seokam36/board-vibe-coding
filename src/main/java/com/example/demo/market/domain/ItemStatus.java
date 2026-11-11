package com.example.demo.market.domain;

// 물건 글의 거래 상태. 코드는 DB·API에서, label은 화면용 한글 이름.
// 값을 늘리려면 여기와 DB의 CHECK 제약(ck_market_items_status, 새 V 파일)을 같이 바꿔야 한다.
public enum ItemStatus {
    ON_SALE("판매중"),
    RESERVED("예약중"),
    DONE("거래완료");

    private final String label;

    ItemStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
