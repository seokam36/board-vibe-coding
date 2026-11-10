package com.example.demo.visa.dto;

import java.time.LocalDate;

import com.example.demo.visa.domain.VisaPlan;

// 내 비자 일정 응답. 일정이 없으면 expiryDate가 null이다(404가 아니라 200 + null).
// 할 일 목록·남은 일수·띠 단계는 S12-b에서 필드를 늘린다.
public record VisaPlanResponse(LocalDate expiryDate) {

    public static VisaPlanResponse from(VisaPlan plan) {
        return new VisaPlanResponse(plan.getExpiryDate());
    }

    // 일정이 아직 없는 회원
    public static VisaPlanResponse empty() {
        return new VisaPlanResponse(null);
    }
}
