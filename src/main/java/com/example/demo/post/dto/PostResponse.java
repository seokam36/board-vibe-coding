package com.example.demo.post.dto;

import java.time.Instant;

import com.example.demo.post.domain.Post;
import com.example.demo.user.dto.AuthorResponse;

// 게시글 응답. 엔티티를 그대로 내보내지 않고 필요한 값만 담는다.
// author는 {id, nickname} 또는 null(작성자가 없는 옛 글). 로그인 아이디(username)는 숨긴다.
public record PostResponse(Long id, String title, String content, Instant createdAt, AuthorResponse author) {

    public static PostResponse from(Post post) {
        AuthorResponse author = post.getAuthor() == null ? null : AuthorResponse.from(post.getAuthor());
        return new PostResponse(post.getId(), post.getTitle(), post.getContent(), post.getCreatedAt(), author);
    }
}
