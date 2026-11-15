package com.example.demo.chat.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.chat.dto.ChatRequest;
import com.example.demo.chat.dto.ChatResponse;
import com.example.demo.chat.service.ChatService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 챗봇 HTTP 요청을 받는 진입점 (/chat). 로그인한 회원만 쓴다: SecurityConfig 공개 목록에 없으면 자동으로 로그인 필요(401).
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    // 질문을 받아 200 + {answer, sources}를 돌려준다. 이 서비스 호출 전체가 트랜잭션 밖이다(AI 호출이 느려서).
    // 로그인 회원 id를 넘기는 이유: 회원별 질문 횟수 제한(429). 형식 검사(@Valid 400)가 먼저 통과해야 이 메서드가 불린다.
    @PostMapping
    public ChatResponse chat(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChatRequest request) {
        return chatService.ask(LoginUser.from(jwt).id(), request.question());
    }
}
