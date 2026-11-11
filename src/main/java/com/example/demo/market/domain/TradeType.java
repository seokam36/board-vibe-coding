package com.example.demo.market.domain;

// 거래 방식. 코드(enum 이름)는 DB·API에서 쓰고, label은 화면에 보여 줄 한글 이름이다.
// 값을 늘리려면 여기와 DB의 CHECK 제약(ck_market_items_trade_type, 새 V 파일)을 같이 바꿔야 한다.
public enum TradeType {
    SALE("판매"),
    FREE("나눔");

    private final String label;

    TradeType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
