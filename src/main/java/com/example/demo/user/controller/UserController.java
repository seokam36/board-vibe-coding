package com.example.demo.user.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AuthCookieFactory;
import com.example.demo.user.dto.NicknameUpdateRequest;
import com.example.demo.user.dto.PasswordChangeRequest;
import com.example.demo.user.dto.UserResponse;
import com.example.demo.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// /users/me/** : 로그인한 본인의 정보를 바꾸는 API. 로그인 필요(SecurityConfig의 anyRequest().authenticated()).
@RestController
@RequestMapping("/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final AuthCookieFactory cookieFactory;

    // 닉네임 변경: 200 {id, username, nickname}
    @PutMapping("/nickname")
    public UserResponse updateNickname(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NicknameUpdateRequest request) {
        return userService.updateNickname(LoginUser.from(jwt).id(), request.nickname());
    }

    // 비밀번호 변경: 204 + 두 쿠키 삭제. 모든 기기가 로그아웃되므로 이 기기도 다시 로그인해야 한다.
    @PutMapping("/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PasswordChangeRequest request) {
        userService.changePassword(LoginUser.from(jwt).id(), request);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.delete().toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.deleteRefresh().toString())
                .build();
    }
}
