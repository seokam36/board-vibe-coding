package com.example.demo.auth.service;

import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InternalAuthenticationServiceException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginRequest;
import com.example.demo.user.dto.UserResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 로그인 처리: 아이디·비밀번호를 검증하고 액세스 토큰을 발급한다.
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;

    // 로그인 결과: 응답 본문에 담을 사용자 + 쿠키에 담을 토큰
    public record LoginResult(UserResponse user, String accessToken) {
    }

    public LoginResult login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (BadCredentialsException e) {
            // 없는 아이디와 틀린 비밀번호를 구분하지 않는다 (어떤 아이디가 있는지 알려 주지 않기 위해)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다");
        } catch (InternalAuthenticationServiceException e) {
            // DB 오류 같은 내부 문제는 AuthenticationException의 하위 클래스라 그대로 던지면
            // Spring Security 필터가 401로 바꿔 버린다. 그래서 일반 예외로 바꿔 던져 500이 되게 한다.
            // 주의: 원인(e)을 cause로 붙이면 ExceptionTranslationFilter가 원인 사슬에서
            // AuthenticationException을 찾아내 다시 401로 바꾼다. 그래서 cause 없이 던지고 원인은 로그로만 남긴다.
            // 상세 원인은 응답 본문에 노출하지 않는다.
            log.error("로그인 처리 중 내부 오류", e);
            throw new IllegalStateException("로그인 처리 중 서버 오류");
        }
        AuthUserDetails principal = (AuthUserDetails) authentication.getPrincipal();
        String token = tokenService.issueAccessToken(principal.id(), principal.username());
        return new LoginResult(new UserResponse(principal.id(), principal.username()), token);
    }
}
