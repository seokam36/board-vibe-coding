package com.example.demo.comment.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.comment.dto.CommentCreateRequest;
import com.example.demo.comment.dto.CommentResponse;
import com.example.demo.comment.dto.CommentUpdateRequest;
import com.example.demo.comment.service.CommentService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 댓글 HTTP 요청을 받는 진입점. 목록·작성은 글 아래(/posts/{postId}/comments), 수정·삭제는 /comments/{id}
@RestController
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    // 목록 조회 (공개)
    @GetMapping("/posts/{postId}/comments")
    public List<CommentResponse> getAll(@PathVariable Long postId) {
        return commentService.getAll(postId);
    }

    // 작성: 201 + Location 헤더로 새 댓글 주소를 알려준다
    @PostMapping("/posts/{postId}/comments")
    public ResponseEntity<CommentResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId,
            @Valid @RequestBody CommentCreateRequest request) {
        CommentResponse response = commentService.create(postId, LoginUser.from(jwt), request);
        return ResponseEntity.created(URI.create("/comments/" + response.id())).body(response);
    }

    // 수정 (내 댓글만)
    @PutMapping("/comments/{id}")
    public CommentResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody CommentUpdateRequest request) {
        return commentService.update(id, LoginUser.from(jwt), request);
    }

    // 삭제: 204 (내 댓글만)
    @DeleteMapping("/comments/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        commentService.delete(id, LoginUser.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
