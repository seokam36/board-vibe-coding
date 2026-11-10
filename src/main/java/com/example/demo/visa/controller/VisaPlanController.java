package com.example.demo.visa.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.visa.dto.VisaPlanRequest;
import com.example.demo.visa.dto.VisaPlanResponse;
import com.example.demo.visa.service.VisaPlanService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 내 비자 일정 HTTP 요청을 받는 진입점 (/users/me/visa-plan). 전부 로그인 필요.
// /visa-reviews 아래에 두지 않은 이유: 그 아래 한 칸짜리 GET은 공개 규칙({id})에 걸려 로그인 없이 열린다.
// /users/me/** 는 SecurityConfig의 anyRequest().authenticated()에 걸려 비로그인이면 401이다.
@RestController
@RequestMapping("/users/me/visa-plan")
@RequiredArgsConstructor
public class VisaPlanController {

    private final VisaPlanService visaPlanService;

    // 조회: 200. 일정이 없으면 {expiryDate, daysLeft, stage 모두 null, todos []}
    @GetMapping
    public VisaPlanResponse get(@AuthenticationPrincipal Jwt jwt) {
        return visaPlanService.get(LoginUser.from(jwt));
    }

    // 등록·덮어쓰기: 200 + 저장된 일정 (학교 인증 회원만)
    @PutMapping
    public VisaPlanResponse save(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody VisaPlanRequest request) {
        return visaPlanService.save(LoginUser.from(jwt), request);
    }

    // 삭제: 성공하면 본문 없이 204, 일정이 없으면 404
    @DeleteMapping
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt) {
        visaPlanService.delete(LoginUser.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
