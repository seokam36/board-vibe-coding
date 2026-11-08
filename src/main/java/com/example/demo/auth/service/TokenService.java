package com.example.demo.auth.service;

import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.config.JwtProperties;
import com.example.demo.user.domain.UserRole;

import lombok.RequiredArgsConstructor;

// 액세스 토큰(JWT)을 만든다. 담는 값: sub=사용자 id, username, role, 발급 시각(iat), 만료 시각(exp)
@Service
@RequiredArgsConstructor
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties props;

    public String issueAccessToken(Long userId, String username, UserRole role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(String.valueOf(userId))
                .claim(LoginUser.USERNAME_CLAIM, username)
                .claim(LoginUser.ROLE_CLAIM, role.name())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTokenExpiry()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
