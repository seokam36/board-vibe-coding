package com.example.demo.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 게시글 작성 요청 본문. 잘못된 입력은 @Valid 검증에서 400으로 걸러진다.
// PostgreSQL은 NUL(\u0000) 문자를 저장하지 못해 500이 나므로, 미리 막아 400으로 돌려준다.
public record PostCreateRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String title,
        @NotBlank @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String content) {
}
