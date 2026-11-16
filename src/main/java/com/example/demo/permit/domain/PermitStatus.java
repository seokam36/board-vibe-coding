package com.example.demo.permit.domain;

// 허가서 신청 한 건의 상태 (permit_requests.status). 이번 단계(S20-a)는 항상 PENDING으로만 만든다.
public enum PermitStatus {
    PENDING,  // 대기
    APPROVED, // 승인
    REJECTED  // 거절
}
