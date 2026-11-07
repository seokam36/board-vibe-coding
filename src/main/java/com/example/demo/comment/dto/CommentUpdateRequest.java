package com.example.demo.comment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 댓글 수정 요청 본문. 작성과 같은 규칙.
public record CommentUpdateRequest(
        @NotBlank @Size(max = 1000) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String content) {
}
