package com.example.demo.user.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AuthCookieFactory;
import com.example.demo.user.dto.NicknameUpdateRequest;
import com.example.demo.user.dto.PasswordChangeRequest;
import com.example.demo.user.dto.SchoolCodeConfirmRequest;
import com.example.demo.user.dto.SchoolEmailCodeRequest;
import com.example.demo.user.dto.UserResponse;
import com.example.demo.user.dto.WithdrawRequest;
import com.example.demo.user.service.EmailVerificationService;
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
    private final EmailVerificationService emailVerificationService;

    // 닉네임 변경: 200 {id, username, nickname}
    @PutMapping("/nickname")
    public UserResponse updateNickname(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody NicknameUpdateRequest request) {
        return userService.updateNickname(LoginUser.from(jwt).id(), request.nickname());
    }

    // 학교 메일 인증 코드 요청: 202 본문 없음 (메일 발송 요청을 "접수"했다는 뜻)
    @PostMapping("/school-verification/code")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void requestSchoolCode(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SchoolEmailCodeRequest request) {
        emailVerificationService.sendCode(LoginUser.from(jwt).id(), request.email());
    }

    // 학교 메일 인증 코드 확인: 200 + 인증 학교가 채워진 UserResponse
    @PostMapping("/school-verification")
    public UserResponse confirmSchoolCode(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SchoolCodeConfirmRequest request) {
        return emailVerificationService.confirmCode(LoginUser.from(jwt).id(), request.code());
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

    // 회원 탈퇴: 204 + 두 쿠키 삭제. 본문의 비밀번호로 본인 확인을 한다.
    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody WithdrawRequest request) {
        userService.withdraw(LoginUser.from(jwt).id(), request);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookieFactory.delete().toString())
                .header(HttpHeaders.SET_COOKIE, cookieFactory.deleteRefresh().toString())
                .build();
    }
}
