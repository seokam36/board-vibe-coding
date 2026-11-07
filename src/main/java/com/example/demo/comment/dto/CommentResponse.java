package com.example.demo.comment.dto;

import java.time.Instant;

import com.example.demo.comment.domain.Comment;
import com.example.demo.user.dto.AuthorResponse;

// 댓글 응답. 엔티티를 그대로 내보내지 않는다. author는 {id, nickname} 또는 null(탈퇴한 회원의 댓글).
public record CommentResponse(Long id, Long postId, String content, Instant createdAt, AuthorResponse author) {

    public static CommentResponse from(Comment comment) {
        AuthorResponse author = comment.getAuthor() == null ? null : AuthorResponse.from(comment.getAuthor());
        // post.getId()는 프록시여도 DB를 다시 읽지 않는다
        return new CommentResponse(comment.getId(), comment.getPost().getId(), comment.getContent(),
                comment.getCreatedAt(), author);
    }
}
