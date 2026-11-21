package com.example.demo.push.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.push.dto.DeviceTokenRequest;
import com.example.demo.push.service.DeviceTokenService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 기기 토큰(푸시 알림용 휴대폰 주소) 등록·삭제 (/users/me/device-tokens). 전부 로그인 필요.
// /users/me/** 는 SecurityConfig의 anyRequest().authenticated()에 걸려 비로그인이면 401이다.
@RestController
@RequestMapping("/users/me/device-tokens")
@RequiredArgsConstructor
public class DeviceTokenController {

    private final DeviceTokenService deviceTokenService;

    // 등록·주인 바꾸기: 본문 없이 204
    @PutMapping
    public ResponseEntity<Void> register(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceTokenRequest request) {
        deviceTokenService.register(LoginUser.from(jwt), request);
        return ResponseEntity.noContent().build();
    }

    // 삭제: 내 토큰이면 지우고, 없거나 남의 것이어도 똑같이 204
    @DeleteMapping
    public ResponseEntity<Void> remove(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceTokenRequest request) {
        deviceTokenService.remove(LoginUser.from(jwt), request);
        return ResponseEntity.noContent().build();
    }
}
