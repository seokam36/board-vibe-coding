package com.example.demo.auth.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginRequest;
import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AuthCookieFactory;
import com.example.demo.auth.service.AuthService;
import com.example.demo.user.dto.SignupRequest;
import com.example.demo.user.dto.UserResponse;
import com.example.demo.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// /auth/** HTTP 진입점. user 패키지에서 옮겨 왔다:
// 토큰·쿠키·CSRF 같은 "인증 방식"은 auth 도메인, User 엔티티·저장소·가입 로직은 user 도메인이 맡는 기준이다.
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthService authService;
    private final AuthCookieFactory cookieFactory;

    // 회원가입: 201 Created + {id, username}
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse signup(@Valid @RequestBody SignupRequest request) {
        return userService.signup(request);
    }

    // 로그인: 200 {id, username} + access_token·refresh_token 쿠키. 아이디/비밀번호가 틀리면 401.
    @PostMapping("/login")
    public ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthService.LoginResult result = authService.login(request);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.create(result.accessToken()).toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.createRefresh(result.refreshToken()).toString())
                .body(result.user());
    }

    // 재발급: refresh_token 쿠키로 새 토큰 한 쌍을 받는다. 실패하면 이유 구분 없이 401 + 두 쿠키 삭제.
    @PostMapping("/refresh")
    public ResponseEntity<UserResponse> refresh(
            @CookieValue(name = AuthCookieFactory.REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        return authService.refresh(refreshToken)
                .map(result -> ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, cookieFactory.create(result.accessToken()).toString())
                        .header(HttpHeaders.SET_COOKIE, cookieFactory.createRefresh(result.refreshToken()).toString())
                        .body(result.user()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .header(HttpHeaders.SET_COOKIE, cookieFactory.delete().toString())
                        .header(HttpHeaders.SET_COOKIE, cookieFactory.deleteRefresh().toString())
                        .build());
    }

    // 로그아웃: Redis의 리프레시 토큰을 지우고 두 쿠키를 지운다. 로그인 안 한 상태여도 204 (지울 게 없어도 결과가 같다).
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = AuthCookieFactory.REFRESH_TOKEN_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.delete().toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.deleteRefresh().toString())
                .build();
    }

    // 내 정보: 토큰(쿠키)이 유효할 때만 도달한다. 아니면 Security가 401을 돌려준다.
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        LoginUser me = LoginUser.from(jwt);
        return new UserResponse(me.id(), me.username());
    }
}
