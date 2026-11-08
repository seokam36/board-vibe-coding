package com.example.demo.report.dto;

import com.example.demo.report.domain.ReportReason;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 신고 요청 본문. reason은 필수(목록 밖 문자열은 JSON 변환 단계에서 400), detail은 선택(최대 300자).
// PostgreSQL은 NUL 문자를 저장하지 못해 500이 나므로 미리 막아 400으로 돌려준다. (null이면 @Size·@Pattern은 통과)
public record ReportCreateRequest(
        @NotNull ReportReason reason,
        @Size(max = 300) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String detail) {
}
