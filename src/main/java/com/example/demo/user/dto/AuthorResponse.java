package com.example.demo.user.dto;

import com.example.demo.user.domain.User;

// 글 응답에 담는 작성자 정보. 로그인 아이디(username)는 숨기고 id와 닉네임만 보여 준다.
public record AuthorResponse(Long id, String nickname) {

    public static AuthorResponse from(User user) {
        return new AuthorResponse(user.getId(), user.getNickname());
    }
}
