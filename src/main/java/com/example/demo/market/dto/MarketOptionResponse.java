package com.example.demo.market.dto;

import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.TradeType;

// 거래 방식·상태 값 하나. {code, name}: code는 API 값, name은 화면용 한글 이름 (VisaOptionResponse와 같은 모양).
public record MarketOptionResponse(String code, String name) {

    public static MarketOptionResponse from(TradeType type) {
        return new MarketOptionResponse(type.name(), type.getLabel());
    }

    public static MarketOptionResponse from(ItemStatus status) {
        return new MarketOptionResponse(status.name(), status.getLabel());
    }
}
