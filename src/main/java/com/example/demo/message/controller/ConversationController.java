package com.example.demo.message.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.message.dto.ConversationStartResponse;
import com.example.demo.message.dto.MessageSendRequest;
import com.example.demo.message.service.ConversationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 쪽지(대화방) HTTP 요청을 받는 진입점. 모두 로그인 필요(SecurityConfig 기본 규칙).
@RestController
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    // 중고 글 판매자에게 쪽지 보내기: 새 방이든 기존 방이든 201.
    // 대화방 한 건을 조회하는 API(GET /conversations/{id})가 아직 없어서 Location 헤더는 붙이지 않는다.
    @PostMapping("/market-items/{id}/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public ConversationStartResponse start(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody MessageSendRequest request) {
        return conversationService.start(id, LoginUser.from(jwt), request.content());
    }
}
