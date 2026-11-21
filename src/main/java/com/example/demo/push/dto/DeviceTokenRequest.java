package com.example.demo.push.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 기기 토큰 등록·삭제 요청 본문: {"token": "..."}. 비어 있거나 공백뿐이면 400, 4096자(DB 칸 크기)를 넘어도 400.
// PostgreSQL은 NUL(\u0000) 문자를 저장·비교하지 못해 500이 나므로 미리 막아 400으로 돌려준다.
public record DeviceTokenRequest(
        @NotBlank @Size(max = 4096) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String token) {

    // record 기본 toString은 토큰을 그대로 찍으므로, 로그에 새지 않게 가린다
    @Override
    public String toString() {
        return "DeviceTokenRequest[token=***]";
    }
}
