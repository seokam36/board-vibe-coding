package com.example.demo.post.dto;

import java.time.Instant;

import com.example.demo.post.domain.Post;
import com.example.demo.user.dto.UserResponse;

// 게시글 응답. 엔티티를 그대로 내보내지 않고 필요한 값만 담는다.
// author는 {id, username} 또는 null(작성자가 없는 옛 글). 비밀번호 등은 UserResponse에 없다.
public record PostResponse(Long id, String title, String content, Instant createdAt, UserResponse author) {

    public static PostResponse from(Post post) {
        UserResponse author = post.getAuthor() == null ? null : UserResponse.from(post.getAuthor());
        return new PostResponse(post.getId(), post.getTitle(), post.getContent(), post.getCreatedAt(), author);
    }
}
