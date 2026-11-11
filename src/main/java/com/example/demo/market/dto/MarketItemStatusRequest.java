package com.example.demo.market.dto;

import com.example.demo.market.domain.ItemStatus;

import jakarta.validation.constraints.NotNull;

// 상태 바꾸기 요청 본문. 없는 상태 값("XXX")은 JSON 변환 단계에서, 빈 값(null)은 @NotNull이 400으로 걸러 낸다.
public record MarketItemStatusRequest(@NotNull ItemStatus status) {
}
