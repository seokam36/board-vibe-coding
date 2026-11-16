package com.example.demo.permit.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// 허가서 승인 요청 본문 (S20-b). 관리자가 사진을 보고 확인한 학교와 허가서 번호.
// 번호 원문은 저장하지 않고 정규화한 해시만 저장한다.
public record PermitApproveRequest(
        @NotNull Long schoolId,
        @NotBlank @Size(max = 50) String permitNumber) {
}
