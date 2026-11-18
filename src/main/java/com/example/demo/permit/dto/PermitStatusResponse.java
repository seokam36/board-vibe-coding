package com.example.demo.permit.dto;

import java.time.Instant;

import com.example.demo.permit.domain.PermitRejectReason;
import com.example.demo.permit.domain.PermitRequest;
import com.fasterxml.jackson.annotation.JsonInclude;

// 허가서 인증 상태 응답. 값이 없는 칸(null)은 JSON에서 빠진다.
// 사진 이름·기계 판독 결과·관리자 정보는 담지 않는다. rejectReason은 REJECTED·REVOKED일 때만 코드로 나간다.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PermitStatusResponse(PermitViewStatus status, PermitRejectReason rejectReason, Instant requestedAt,
        Instant decidedAt) {

    public static PermitStatusResponse none() {
        return new PermitStatusResponse(PermitViewStatus.NONE, null, null, null);
    }

    // 승인된 회원인데 신청 행이 없는 경우(예전 메일 인증 회원·운영자 SQL 등)
    public static PermitStatusResponse approvedWithoutRequest() {
        return new PermitStatusResponse(PermitViewStatus.APPROVED, null, null, null);
    }

    // 신청 행 하나를 화면 상태로 바꾼다
    public static PermitStatusResponse from(PermitRequest request) {
        PermitViewStatus view = PermitViewStatus.valueOf(request.getStatus().name());
        // 거절(REJECTED)과 취소(REVOKED, S21-a)일 때만 사유 코드를 내보낸다
        PermitRejectReason reason = view == PermitViewStatus.REJECTED || view == PermitViewStatus.REVOKED
                ? request.getRejectReason() : null;
        return new PermitStatusResponse(view, reason, request.getCreatedAt(), request.getDecidedAt());
    }

    // 승인 상태로 보여 주되 시각은 승인된 신청 행에서 가져온다
    public static PermitStatusResponse approvedFrom(PermitRequest request) {
        return new PermitStatusResponse(PermitViewStatus.APPROVED, null, request.getCreatedAt(), request.getDecidedAt());
    }
}
