package com.example.demo.auth.service;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.user.domain.UserRole;

// Spring Security가 로그인 검증에 쓰는 사용자 정보. 토큰의 sub에 넣을 id를 함께 들고 있다.
// (우리 엔티티 User와 이름이 겹치는 Security의 User 클래스를 쓰지 않으려고 직접 구현했다)
// verifiedSchool은 로그인 응답에 담을 인증 학교(미인증이면 null). 트랜잭션 안에서 미리 DTO로 바꿔 둔다.
public record AuthUserDetails(Long id, String username, String nickname, String password, UserRole role,
        SchoolResponse verifiedSchool) implements UserDetails {

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(); // 권한 판단은 Security 규칙이 아니라 서비스(AccessGuard)가 role로 직접 한다
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    // record 기본 toString은 비밀번호 해시를 찍으므로 가린다
    @Override
    public String toString() {
        return "AuthUserDetails[id=" + id + ", username=" + username + "]";
    }
}
