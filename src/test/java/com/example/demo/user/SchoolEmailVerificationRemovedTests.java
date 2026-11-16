package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.permit.AdminPermitTestBase;
import com.example.demo.user.domain.User;

import jakarta.servlet.http.Cookie;

// S20-f1 완료 조건: 학교 메일 인증(S10) API가 사라졌고, 예전 메일 인증 회원·허가서 인증은 그대로 동작한다.
// (GET /schools의 emailDomains 제거는 SchoolVerificationApiTests, 메일 설정 없이 컨텍스트가 뜨는 것은 이 클래스가 뜨는 것 자체로 확인된다.)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SchoolEmailVerificationRemovedTests extends AdminPermitTestBase {

	private MockHttpServletResponse 보내기(String url, Cookie c, String json) throws Exception {
		return mockMvc.perform(post(url).with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON).content(json))
				.andReturn().getResponse();
	}

	private Map<String, Object> 인증칸(User u) {
		return jdbc.queryForMap("select verified_school_id, verified_at, school_email_hash from users where id = ?", u.getId());
	}

	private int 글쓰기(User u, long schoolId) throws Exception {
		return 보내기("/posts", 쿠키(u),
				"{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + schoolId + ",\"category\":\"CHAT\"}").getStatus();
	}

	// 완료 조건 1: 메일 인증 API 2개는 이제 없는 주소(404)이고, 인증 3칸은 그대로다
	@Test
	void 메일_인증_코드_요청과_확인_API는_404이고_인증_칸은_변하지_않는다() throws Exception {
		User u = 회원("mailgone");
		Map<String, Object> before = 인증칸(u);

		assertThat(보내기("/users/me/school-verification/code", 쿠키(u), "{\"email\":\"a@yonsei.ac.kr\"}").getStatus())
				.isEqualTo(404);
		assertThat(보내기("/users/me/school-verification", 쿠키(u), "{\"code\":\"123456\"}").getStatus()).isEqualTo(404);

		assertThat(인증칸(u)).isEqualTo(before);
		assertThat(before.values()).allMatch(v -> v == null);
	}

	// 완료 조건 3: 신청 행 없이 학교만 채워진 회원(예전 메일 인증 회원)은 글쓰기 201, 상태 APPROVED
	@Test
	void 예전_메일_인증_회원은_신청_행이_없어도_글쓰기_201이고_상태는_APPROVED다() throws Exception {
		User u = TestLogin.인증된_사용자(userRepository, jdbc, "oldmail", "연세대학교");
		assertThat(jdbc.queryForObject("select count(*) from permit_requests where user_id = ?", Long.class, u.getId()))
				.isZero();

		assertThat(글쓰기(u, 연세id)).isEqualTo(201);
		MockHttpServletResponse r = 내상태(u);
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(json(r).get("status").asString()).isEqualTo("APPROVED");
	}

	// 완료 조건 4: 미인증은 403, 관리자 승인 뒤에는 201
	@Test
	void 미인증_회원은_글쓰기_403이고_관리자_승인_뒤에는_201이다() throws Exception {
		User u = 회원("needpermit");
		assertThat(글쓰기(u, 연세id)).isEqualTo(403);

		long id = 신청(u);
		assertThat(승인(id, 연세id, "S20F-0001").getStatus()).isEqualTo(200);

		assertThat(글쓰기(u, 연세id)).isEqualTo(201);
	}

	// 완료 조건 5: school_email_hash가 채워진 회원도 철회하면 3칸이 모두 NULL
	@Test
	void 메일_해시가_있는_인증_회원이_철회하면_세_칸이_모두_NULL이다() throws Exception {
		User u = TestLogin.인증된_사용자(userRepository, jdbc, "hashed", "연세대학교");
		assertThat(인증칸(u).get("school_email_hash")).isNotNull();

		MockHttpServletResponse r = mockMvc.perform(delete(ME_URL).with(csrf()).cookie(쿠키(u))).andReturn().getResponse();

		assertThat(r.getStatus()).isEqualTo(204);
		assertThat(인증칸(u).values()).hasSize(3).allMatch(v -> v == null);
	}
}
