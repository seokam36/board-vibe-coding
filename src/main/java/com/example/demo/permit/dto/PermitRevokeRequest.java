package com.example.demo.permit.dto;

import com.example.demo.permit.domain.PermitRejectReason;

import jakarta.validation.constraints.NotNull;

// 승인된 인증 취소 요청 본문 (S21-a). reason은 필수이고 고정 목록 밖의 값은 JSON 변환 단계에서 400.
// 반려 사유 코드를 그대로 쓰되 EXPIRED(대기 만료 전용)는 서비스가 400으로 막는다.
public record PermitRevokeRequest(@NotNull PermitRejectReason reason) {
}
