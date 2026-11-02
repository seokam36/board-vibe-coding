package com.example.demo.post.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 게시글 수정 요청 본문. 검증 규칙은 작성 요청(PostCreateRequest)과 같고, 둘 다 필수다.
// PostgreSQL은 NUL(\u0000) 문자를 저장하지 못해 500이 나므로, 미리 막아 400으로 돌려준다.
public record PostUpdateRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String title,
        @NotBlank @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String content) {
}
