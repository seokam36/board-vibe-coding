package com.example.demo.visa.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.visa.dto.VisaReviewCreateRequest;
import com.example.demo.visa.dto.VisaReviewResponse;
import com.example.demo.visa.service.VisaReviewService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 비자 후기 HTTP 요청을 받는 진입점 (/visa-reviews)
@RestController
@RequestMapping("/visa-reviews")
@RequiredArgsConstructor
public class VisaReviewController {

    private final VisaReviewService visaReviewService;

    // 작성: 201 Created + Location 헤더로 새 후기 주소를 알려준다. 학교 인증 회원만 가능.
    @PostMapping
    public ResponseEntity<VisaReviewResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VisaReviewCreateRequest request) {
        VisaReviewResponse response = visaReviewService.create(LoginUser.from(jwt), request);
        return ResponseEntity.created(URI.create("/visa-reviews/" + response.id())).body(response);
    }

    // 단건 조회 (로그인 없이 공개)
    @GetMapping("/{id}")
    public VisaReviewResponse get(@PathVariable Long id) {
        return visaReviewService.get(id);
    }
}
