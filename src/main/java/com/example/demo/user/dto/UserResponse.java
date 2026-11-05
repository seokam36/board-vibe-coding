package com.example.demo.user.dto;

import com.example.demo.user.domain.User;

// 회원 응답. 비밀번호(해시 포함)는 담지 않는다.
public record UserResponse(Long id, String username, String nickname) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getNickname());
    }
}
