package com.example.demo.post.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.post.dto.PostCreateRequest;
import com.example.demo.post.dto.PostResponse;
import com.example.demo.post.service.PostService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 게시글 HTTP 요청을 받는 진입점 (/posts)
@RestController
@RequestMapping("/posts")
@RequiredArgsConstructor // final 필드 생성자 주입 자동 생성
public class PostController {

    private final PostService postService;

    // 작성: 201 Created + Location 헤더로 새 글 주소를 알려준다
    @PostMapping
    public ResponseEntity<PostResponse> create(@Valid @RequestBody PostCreateRequest request) {
        PostResponse response = postService.create(request);
        return ResponseEntity.created(URI.create("/posts/" + response.id())).body(response);
    }

    // 단건 조회
    @GetMapping("/{id}")
    public PostResponse get(@PathVariable Long id) {
        return postService.get(id);
    }

    // 목록 조회 (최신순)
    @GetMapping
    public List<PostResponse> getAll() {
        return postService.getAll();
    }
}
