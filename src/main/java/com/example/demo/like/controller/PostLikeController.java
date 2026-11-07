package com.example.demo.like.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.like.dto.LikeResponse;
import com.example.demo.like.dto.LikedResponse;
import com.example.demo.like.service.PostLikeService;

import lombok.RequiredArgsConstructor;

// 공감 HTTP 요청을 받는 진입점. 모두 로그인 필요(공개 경로 아님)
@RestController
@RequiredArgsConstructor
public class PostLikeController {

    private final PostLikeService postLikeService;

    // 공감 누르기: 201
    @PostMapping("/posts/{postId}/likes")
    public ResponseEntity<LikeResponse> like(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(postLikeService.like(postId, LoginUser.from(jwt)));
    }

    // 공감 취소: 200 + 처리 후 개수
    @DeleteMapping("/posts/{postId}/likes")
    public LikeResponse unlike(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId) {
        return postLikeService.unlike(postId, LoginUser.from(jwt));
    }

    // 내가 공감했는지
    @GetMapping("/posts/{postId}/likes/me")
    public LikedResponse isLiked(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId) {
        return postLikeService.isLiked(postId, LoginUser.from(jwt));
    }
}
