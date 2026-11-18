package com.example.demo.permit.domain;

// 허가서 신청 한 건의 상태 (permit_requests.status). 새 신청은 항상 PENDING으로 시작하고, 승인·반려·취소로 바뀐다.
public enum PermitStatus {
    PENDING,  // 대기
    APPROVED, // 승인
    REJECTED, // 거절
    REVOKED   // 취소 (S21-a: 승인됐다가 관리자가 되돌림)
}
