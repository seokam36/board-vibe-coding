package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.example.demo.user.domain.UserRole;

// UserRole.fromClaim 단위 테스트 (스프링 없이). 토큰의 role 문자열을 역할로 바꾸는 규칙이 맞는지 확인한다.
class UserRoleTests {

	@Test
	void ADMIN_문자열은_ADMIN_역할이다() {
		assertThat(UserRole.fromClaim("ADMIN")).contains(UserRole.ADMIN);
	}

	@Test
	void USER_문자열은_USER_역할이다() {
		assertThat(UserRole.fromClaim("USER")).contains(UserRole.USER);
	}

	// 없는 값·빈 값·소문자·모르는 값은 모두 "유효하지 않음"(empty) -> 호출한 쪽에서 USER로 낮춘다
	@Test
	void null은_empty다() {
		assertThat(UserRole.fromClaim(null)).isEmpty();
	}

	@Test
	void 빈_문자열은_empty다() {
		assertThat(UserRole.fromClaim("")).isEmpty();
	}

	@Test
	void 소문자_admin은_empty다() {
		assertThat(UserRole.fromClaim("admin")).isEmpty();
	}

	@Test
	void 모르는_값_SUPERUSER는_empty다() {
		assertThat(UserRole.fromClaim("SUPERUSER")).isEmpty();
	}
}
