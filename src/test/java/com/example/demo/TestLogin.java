package com.example.demo;

import java.time.Instant;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;

import com.example.demo.auth.service.TokenService;
import com.example.demo.school.domain.School;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.domain.UserRole;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// 테스트용 로그인 도우미. 사용자를 DB에 만들고, 그 사람의 access_token 쿠키를 돌려준다.
// (화면 로그인 API를 거치지 않고 토큰만 바로 만들어서 글 API 테스트를 짧게 쓴다)
public final class TestLogin {

	private TestLogin() {
	}

	// 사용자를 저장하고 반환한다. 닉네임은 아이디와 같게 둔다(서로 달라서 중복되지 않음). 비밀번호는 로그인에 쓰지 않으므로 아무 값이나 넣는다.
	public static User 사용자(UserRepository userRepository, String username) {
		return userRepository.save(new User(username, username, "{noop}unused-password"));
	}

	// 서명은 맞는 정상 토큰인데 그 회원 행은 DB에 없는 "유령 토큰"을 만든다(탈퇴 직후 옛 토큰 상황)
	public static Cookie 행없는_회원_토큰(JwtEncoder jwtEncoder) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("999999999").claim("username", "ghost").issuedAt(now)
				.expiresAt(now.plusSeconds(900)).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new Cookie("access_token", token);
	}

	// 그 사용자 이름으로 발급한 access_token 쿠키
	public static Cookie 쿠키(TokenService tokenService, User user) {
		return new Cookie("access_token", tokenService.issueAccessToken(user.getId(), user.getUsername(), user.getRole()));
	}

	// 토큰에 넣을 role을 직접 정한 쿠키. DB 값과 다른 role도 만들 수 있다(예: DB는 USER인데 토큰만 ADMIN인 "묵은 토큰")
	public static Cookie 쿠키(TokenService tokenService, User user, UserRole role) {
		return new Cookie("access_token", tokenService.issueAccessToken(user.getId(), user.getUsername(), role));
	}

	// 관리자 회원을 만든다. 가입으로는 ADMIN을 만들 수 없으므로 운영과 같이 DB를 직접 고친다(UPDATE).
	public static User 관리자(UserRepository userRepository, JdbcTemplate jdbc, String username) {
		User user = 사용자(userRepository, username);
		jdbc.update("update users set role = 'ADMIN' where id = ?", user.getId());
		return user;
	}

	// 관리자 회원의 access_token 쿠키(토큰 role = ADMIN)
	public static Cookie 관리자_쿠키(TokenService tokenService, User admin) {
		return 쿠키(tokenService, admin, UserRole.ADMIN);
	}

	// 학교 인증을 마친 회원을 만든다(S10-d 글쓰기 테스트용). 인증 API 없이 운영처럼 DB 칸을 직접 채운다(UPDATE).
	// schoolName 예: "연세대학교". 메일 해시는 아이디마다 달라야 UNIQUE에 안 걸리므로 아이디로 만든 가짜 값(64자)을 넣는다.
	public static User 인증된_사용자(UserRepository userRepository, JdbcTemplate jdbc, String username, String schoolName) {
		User user = 사용자(userRepository, username);
		학교_인증_처리(jdbc, username, schoolName); // 같은 UPDATE를 한 곳에서만 관리한다
		return user;
	}

	// 이름으로 학교를 찾는다(V2가 넣어 둔 학교, 예: "연세대학교"). 글에 학교를 지정하거나 학교 id를 얻을 때 쓴다.
	public static School 학교(SchoolRepository schoolRepository, String schoolName) {
		return schoolRepository.findAll().stream().filter(s -> s.getName().equals(schoolName)).findFirst().orElseThrow();
	}

	// 이미 만들어진 회원(예: 실제 가입으로 만든 회원)을 학교 인증 상태로 바꾼다. 메일 해시는 UNIQUE라 아이디로 만든 가짜 64자.
	public static void 학교_인증_처리(JdbcTemplate jdbc, String username, String schoolName) {
		String fakeHash = String.format("%-64s", username).replace(' ', '0');
		jdbc.update("update users set verified_school_id = (select id from schools where name = ?), "
				+ "verified_at = now(), school_email_hash = ? where username = ?", schoolName, fakeHash, username);
	}
}
