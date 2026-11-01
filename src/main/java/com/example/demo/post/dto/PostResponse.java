package com.example.demo.post.dto;

import java.time.Instant;

import com.example.demo.post.domain.Post;

// 게시글 응답. 엔티티를 그대로 내보내지 않고 필요한 값만 담는다.
public record PostResponse(Long id, String title, String content, Instant createdAt) {

    public static PostResponse from(Post post) {
        return new PostResponse(post.getId(), post.getTitle(), post.getContent(), post.getCreatedAt());
    }
}
