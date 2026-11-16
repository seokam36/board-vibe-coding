package com.example.demo.permit.domain;

// 거절 이유 코드 (permit_requests.reject_reason의 고정 목록. DB CHECK와 같다)
public enum PermitRejectReason {
    BLURRY, NOT_PERMIT, NOT_MASKED, UNSUPPORTED_SCHOOL, DUPLICATE, EXPIRED, OTHER
}
