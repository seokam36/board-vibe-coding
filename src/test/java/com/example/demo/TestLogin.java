package com.example.demo;

import com.example.demo.auth.service.TokenService;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// 테스트용 로그인 도우미. 사용자를 DB에 만들고, 그 사람의 access_token 쿠키를 돌려준다.
// (화면 로그인 API를 거치지 않고 토큰만 바로 만들어서 글 API 테스트를 짧게 쓴다)
public final class TestLogin {

	private TestLogin() {
	}

	// 사용자를 저장하고 반환한다. 비밀번호는 로그인에 쓰지 않으므로 아무 값이나 넣는다.
	public static User 사용자(UserRepository userRepository, String username) {
		return userRepository.save(new User(username, "{noop}unused-password"));
	}

	// 그 사용자 이름으로 발급한 access_token 쿠키
	public static Cookie 쿠키(TokenService tokenService, User user) {
		return new Cookie("access_token", tokenService.issueAccessToken(user.getId(), user.getUsername()));
	}
}
