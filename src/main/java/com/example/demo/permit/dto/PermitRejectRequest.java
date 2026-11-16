package com.example.demo.permit.dto;

import com.example.demo.permit.domain.PermitRejectReason;

import jakarta.validation.constraints.NotNull;

// 허가서 반려 요청 본문 (S20-b). reason은 필수이고 고정 목록 밖의 값은 JSON 변환 단계에서 400.
public record PermitRejectRequest(@NotNull PermitRejectReason reason) {
}
