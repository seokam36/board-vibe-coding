package com.example.demo.message.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 쪽지 보내기 요청 본문. 1~1000자, 공백뿐이면 400.
// PostgreSQL은 NUL(\u0000) 문자를 저장하지 못해 500이 나므로 미리 막아 400으로 돌려준다.
public record MessageSendRequest(
        @NotBlank @Size(max = 1000) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String content) {
}
