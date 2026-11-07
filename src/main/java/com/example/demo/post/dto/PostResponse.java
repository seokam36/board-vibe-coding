package com.example.demo.post.dto;

import java.time.Instant;

import com.example.demo.post.domain.Post;
import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.user.dto.AuthorResponse;

// 게시글 응답. 엔티티를 그대로 내보내지 않고 필요한 값만 담는다.
// author는 {id, nickname} 또는 null(작성자가 없는 옛 글). 로그인 아이디(username)는 숨긴다.
// school은 {id, name} 또는 null(학교가 없는 옛 글), category는 {code, name}.
public record PostResponse(Long id, String title, String content, Instant createdAt, AuthorResponse author,
        SchoolResponse school, CategoryResponse category, long commentCount, long likeCount) {

    // 개수를 모르는 곳(방금 만든 글 등)용: 댓글 수·공감 수를 0으로 채운다
    public static PostResponse from(Post post) {
        return from(post, 0, 0);
    }

    // 댓글 수·공감 수는 표에 칸이 없고 조회 때 세서 넘겨 준다
    public static PostResponse from(Post post, long commentCount, long likeCount) {
        AuthorResponse author = post.getAuthor() == null ? null : AuthorResponse.from(post.getAuthor());
        SchoolResponse school = post.getSchool() == null ? null : SchoolResponse.from(post.getSchool());
        return new PostResponse(post.getId(), post.getTitle(), post.getContent(), post.getCreatedAt(), author,
                school, CategoryResponse.from(post.getCategory()), commentCount, likeCount);
    }
}
