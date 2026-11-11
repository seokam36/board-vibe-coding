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

    // 상태를 바꿀 수 있는 길인지. 전환 규칙은 이 한 곳에만 둔다.
    // 허용: 판매중<->예약중, 판매중->거래완료, 예약중->거래완료. 거래완료에서는 못 나가고, 같은 상태로도 못 바꾼다.
    public boolean canChangeTo(ItemStatus next) {
        return switch (this) {
            case ON_SALE -> next == RESERVED || next == DONE;
            case RESERVED -> next == ON_SALE || next == DONE;
            case DONE -> false;
        };
    }
}
