package com.example.demo.post.controller;

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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.dto.PostCreateRequest;
import com.example.demo.post.dto.PostResponse;
import com.example.demo.post.dto.PostUpdateRequest;
import com.example.demo.post.service.PostService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 게시글 HTTP 요청을 받는 진입점 (/posts)
@RestController
@RequestMapping("/posts")
@RequiredArgsConstructor // final 필드 생성자 주입 자동 생성
public class PostController {

    private final PostService postService;

    // 작성: 201 Created + Location 헤더로 새 글 주소를 알려준다. 작성자는 본문이 아니라 로그인 토큰에서 정한다.
    @PostMapping
    public ResponseEntity<PostResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PostCreateRequest request) {
        PostResponse response = postService.create(LoginUser.from(jwt), request);
        return ResponseEntity.created(URI.create("/posts/" + response.id())).body(response);
    }

    // 단건 조회
    @GetMapping("/{id}")
    public PostResponse get(@PathVariable Long id) {
        return postService.get(id);
    }

    // 수정: 제목·본문을 모두 새 값으로 바꾸고 200 + 수정된 글을 돌려준다 (내 글만 가능)
    @PutMapping("/{id}")
    public PostResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody PostUpdateRequest request) {
        return postService.update(id, LoginUser.from(jwt), request);
    }

    // 삭제: 성공하면 본문 없이 204 No Content (내 글만 가능)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        postService.delete(id, LoginUser.from(jwt));
        return ResponseEntity.noContent().build();
    }

    // 목록 조회 (최신순). schoolId·category는 선택 필터. 숫자·말머리 형식이 틀리면 스프링이 400을 돌려준다.
    @GetMapping
    public List<PostResponse> getAll(@RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) PostCategory category) {
        return postService.getAll(schoolId, category);
    }
}
