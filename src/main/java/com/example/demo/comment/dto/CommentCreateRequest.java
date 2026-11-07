package com.example.demo.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 댓글 작성 요청 본문. 공백만/1001자 이상/NUL 문자는 @Valid 검증에서 400으로 걸러진다.
// (PostgreSQL은 NUL을 저장하지 못해 500이 나므로 미리 막는다)
public record CommentCreateRequest(
        @NotBlank @Size(max = 1000) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String content) {
}
