package com.example.demo.permit.dto;

// 화면에 보여 줄 허가서 인증 상태. 신청 행의 상태(PENDING/APPROVED/REJECTED)에 "신청 없음(NONE)"이 더해진다.
public enum PermitViewStatus {
    NONE, PENDING, REJECTED, APPROVED
}
