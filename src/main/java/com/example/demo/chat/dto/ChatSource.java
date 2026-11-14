package com.example.demo.chat.dto;

import com.example.demo.market.dto.MarketOptionResponse;

// 챗봇 답의 "출처" 한 건 = 서버가 AI에게 실제로 준 글. type으로 글 종류를 구분해서, 나중에 다른 글 종류가 늘어도 모양이 안 바뀐다.
// tradeType·price·status는 중고 글 응답(28번)과 같은 표현이다: {code, name}, 나눔은 price 0.
public record ChatSource(String type, Long id, String title, MarketOptionResponse tradeType, int price,
        MarketOptionResponse status) {

    public static final String MARKET_ITEM = "MARKET_ITEM";

    public static ChatSource from(ChatMarketItem item) {
        return new ChatSource(MARKET_ITEM, item.id(), item.title(), MarketOptionResponse.from(item.tradeType()),
                item.price(), MarketOptionResponse.from(item.status()));
    }
}
