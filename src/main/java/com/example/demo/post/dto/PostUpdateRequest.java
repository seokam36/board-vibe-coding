package com.example.demo.post.dto;

import com.example.demo.post.domain.PostCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 게시글 수정 요청 본문. 제목·본문·말머리가 모두 필수다.
// 학교는 바꿀 수 없어서 필드가 없다(요청에 schoolId를 보내도 알 수 없는 필드로 무시된다).
// PostgreSQL은 NUL(\u0000) 문자를 저장하지 못해 500이 나므로, 미리 막아 400으로 돌려준다.
public record PostUpdateRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String title,
        @NotBlank @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String content,
        @NotNull PostCategory category) {
}
