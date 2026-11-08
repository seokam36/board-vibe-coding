package com.example.demo.auth.service;

import java.util.Optional;

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
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 인증 처리: 로그인(액세스·리프레시 토큰 발급), 리프레시 토큰 재발급, 로그아웃(리프레시 토큰 폐기).
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final TokenService tokenService;
    private final RefreshTokenService refreshTokenService;
    private final UserRepository userRepository;

    // 로그인·재발급 결과: 응답 본문에 담을 사용자 + 쿠키에 담을 두 토큰
    public record LoginResult(UserResponse user, String accessToken, String refreshToken) {

        // 기본 toString은 토큰 원문을 찍으므로 가린다 (로그에 남지 않게)
        @Override
        public String toString() {
            return "LoginResult[user=" + user + ", accessToken=***, refreshToken=***]";
        }
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
        String token = tokenService.issueAccessToken(principal.id(), principal.username(), principal.role());
        String refreshToken = refreshTokenService.issue(principal.id());
        return new LoginResult(new UserResponse(principal.id(), principal.username(), principal.nickname(), principal.role()), token, refreshToken);
    }

    // 로그아웃: 리프레시 토큰이 있으면 Redis에서 지운다(회원별 토큰 목록(Set)에서도 빠진다).
    public void logout(String refreshToken) {
        refreshTokenService.revoke(refreshToken);
    }

    // 재발급: 옛 리프레시 토큰을 꺼내며 지우고(회전) 새 리프레시 + 새 액세스 토큰을 발급한다.
    // 토큰이 없거나 모르거나 이미 썼거나 만료됐거나 사용자가 삭제됐으면 모두 빈 값(이유 구분 없음).
    // 사용자 조회에 실패해도 옛 토큰은 이미 지워진 상태다(어차피 쓸 수 없는 토큰이라 문제 없음).
    // 트랜잭션을 걸지 않는다: Redis 호출 동안 DB 연결(풀 3개)을 붙잡지 않기 위함. findById는 자체 트랜잭션으로 충분.
    public Optional<LoginResult> refresh(String refreshToken) {
        return refreshTokenService.consume(refreshToken)
                .flatMap(userRepository::findById)
                .map(user -> new LoginResult(
                        UserResponse.from(user),
                        tokenService.issueAccessToken(user.getId(), user.getUsername(), user.getRole()),
                        refreshTokenService.issue(user.getId())));
    }
}
