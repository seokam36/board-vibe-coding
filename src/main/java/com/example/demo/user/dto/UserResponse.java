package com.example.demo.user.dto;

import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.user.domain.User;
import com.example.demo.user.domain.UserRole;

// 회원 응답. 비밀번호(해시 포함)는 담지 않는다. role은 화면이 관리자 버튼을 보일지 정하는 데 쓴다.
// verifiedSchool은 학교 인증을 마친 학교 {id, name}, 미인증이면 null (메일 해시 등은 담지 않는다).
public record UserResponse(Long id, String username, String nickname, UserRole role, SchoolResponse verifiedSchool) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getNickname(), user.getRole(), SchoolResponse.fromNullable(user.getVerifiedSchool()));
    }
}
