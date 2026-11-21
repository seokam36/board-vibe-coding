package com.example.demo.push.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.common.ApiErrors;
import com.example.demo.push.dto.DeviceTokenRequest;
import com.example.demo.push.repository.DeviceTokenRepository;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

// 기기 토큰(푸시 알림용 휴대폰 주소) 등록·삭제. 토큰 값은 로그에 남기지 않는다.
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    // 회원 한 명이 가질 수 있는 토큰 수 (넘으면 오래된 것부터 삭제)
    static final int MAX_PER_USER = 10;

    private final DeviceTokenRepository deviceTokenRepository;
    private final UserRepository userRepository;

    // 등록: 없으면 추가, 같은 토큰이 이미 있으면(내 것이든 남의 것이든) 주인을 지금 회원으로 바꾸고 updated_at 갱신.
    // 순서: 회원 행 FOR UPDATE(없으면 401, 탈퇴와 같은 잠금 순서) -> upsert -> 10개 넘는 오래된 것 삭제.
    // 회원 행을 먼저 잠그면 같은 회원의 PUT끼리 줄을 서므로 "11개로 늘었다가 안 줄어드는" 경쟁이 없다.
    @Transactional
    public void register(LoginUser loginUser, DeviceTokenRequest request) {
        Long userId = loginUser.id();
        if (userRepository.lockById(userId).isEmpty()) {
            throw ApiErrors.loginRequired(); // 토큰은 유효한데 회원이 DB에 없음(탈퇴)
        }
        deviceTokenRepository.upsert(userId, request.token());
        deviceTokenRepository.trimToMax(userId, MAX_PER_USER);
    }

    // 삭제: 내 토큰이면 지우고, 없거나 남의 것이면 아무것도 안 한다(항상 성공 = 204).
    // 남의 토큰이 있는지 알려 주지 않으려고 "없음"과 "남의 것"을 구분하지 않는다.
    @Transactional
    public void remove(LoginUser loginUser, DeviceTokenRequest request) {
        deviceTokenRepository.deleteByUserIdAndToken(loginUser.id(), request.token());
    }
}
