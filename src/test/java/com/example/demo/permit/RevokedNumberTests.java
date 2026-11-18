package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

// S21-b 취소된 번호의 자동 승인 막기 검사. 값은 전부 지어낸 것.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakePermitTextReader.Config.class })
@AutoConfigureMockMvc
class RevokedNumberTests extends AdminPermitTestBase {

	@Autowired FakePermitTextReader 판독기;

	private static final String 번호X = "2013-0001"; // 허가서 글자의 "제 2013-0001 호"와 같은 번호
	private static final String 번호Y = "2013-0002";

	@BeforeEach
	void 판독기초기화() {
		판독기.reset();
	}

	// 4관문(제목·국적·학교·번호)을 통과하는 글자를 판독기에 넣는다
	private void 글자넣기(String number) {
		판독기.text = String.join("\n", "표준입학허가서", "국적(Country of citizenship): 베트남", "학교명(School name): 연세대학교",
				"표준입학허가서 번호(Certificate of Admission No.): 제 " + number + " 호");
	}

	private MockHttpServletResponse 업로드(User u) throws Exception {
		return mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
	}

	private MockHttpServletResponse 철회(User u) throws Exception {
		return mockMvc.perform(delete(ME_URL).cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
	}

	private void 취소(long id) throws Exception {
		var r = mockMvc.perform(put(ADMIN_URL + "/" + id + "/revoke").contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"NOT_PERMIT\"}").with(csrf()).cookie(관리자쿠키)).andReturn().getResponse();
		assertThat(r.getStatus()).as("취소 응답").isEqualTo(200);
	}

	// 회원 A가 번호 X로 승인된 뒤 관리자가 취소한 상태를 만들고 A의 신청 id를 돌려준다
	private long 취소된_A(User a) throws Exception {
		long id = 신청(a);
		assertThat(승인(id, 연세id, 번호X).getStatus()).isEqualTo(200);
		취소(id);
		return id;
	}

	private String 기계결과(User u) {
		return jdbc.queryForObject("select machine_result from permit_requests where user_id = ? and status <> 'REVOKED'", String.class, u.getId());
	}

	private long REVOKED수(User u) {
		return jdbc.queryForObject("select count(*) from permit_requests where user_id = ? and status = 'REVOKED'",
				Long.class, u.getId());
	}

	// ---------- 조건 1 ----------
	@Test
	void 취소된_번호로_다른_회원이_신청하면_자동_승인_안_되고_PENDING_NUMBER_DUPLICATE이다() throws Exception {
		취소된_A(회원("ra"));
		User b = 회원("rb");
		글자넣기(번호X);

		MockHttpServletResponse r = 업로드(b);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asString()).isEqualTo("PENDING");
		assertThat(기계결과(b)).isEqualTo("NUMBER_DUPLICATE"); // 회원 응답엔 없어 DB 칸으로 확인
		assertThat(인증학교(b)).as("B 인증 칸은 비어 있다").isNull();
	}

	// ---------- 조건 2 ----------
	@Test
	void 같은_상황에서_관리자가_직접_승인하면_200이고_B가_인증된다() throws Exception {
		취소된_A(회원("ra"));
		User b = 회원("rb");
		글자넣기(번호X);
		assertThat(업로드(b).getStatus()).isEqualTo(201);
		long bId = jdbc.queryForObject("select id from permit_requests where user_id = ?", Long.class, b.getId());

		MockHttpServletResponse r = 승인(bId, 연세id, 번호X);

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(신청행(bId).get("status")).isEqualTo("APPROVED");
		assertThat(인증학교(b)).isEqualTo(연세id);
	}

	// ---------- 조건 3 ----------
	@Test
	void 취소된_회원이_철회하면_404이고_REVOKED_행은_DB에_남는다() throws Exception {
		User a = 회원("ra");
		long id = 취소된_A(a);

		MockHttpServletResponse r = 철회(a);

		assertThat(r.getStatus()).as("REVOKED만 있고 인증 없음").isEqualTo(404);
		assertThat(신청행(id).get("status")).isEqualTo("REVOKED");
		assertThat(REVOKED수(a)).isEqualTo(1);
	}

	// ---------- 조건 4 ----------
	@Test
	void 취소된_회원이_재신청_뒤_철회하면_204이고_PENDING만_지워지고_REVOKED는_남는다() throws Exception {
		User a = 회원("ra");
		long 옛id = 취소된_A(a);
		assertThat(업로드(a).getStatus()).isEqualTo(201);

		assertThat(철회(a).getStatus()).isEqualTo(204);

		assertThat(jdbc.queryForObject("select count(*) from permit_requests where user_id = ? and status = 'PENDING'",
				Long.class, a.getId())).isZero();
		assertThat(신청행(옛id).get("status")).isEqualTo("REVOKED");
		assertThat(json(내상태(a)).get("status").asString()).isEqualTo("REVOKED");
		// 재신청한 대기 사진도 같이 지워졌는지(파일 0개, 삭제 대기표 0개)
		assertThat(파일수()).as("남은 사진 파일").isZero();
		assertThat(대기표수()).as("삭제 대기표").isZero();
	}

	// ---------- review 권장3 ----------
	@Test
	void 취소_뒤_재신청을_다른_관리자가_승인하고_철회하면_REVOKED만_남고_인증이_비워진다() throws Exception {
		User a = 회원("ra");
		취소된_A(a);
		글자넣기(번호X);
		assertThat(업로드(a).getStatus()).isEqualTo(201);
		assertThat(기계결과(a)).isEqualTo("NUMBER_DUPLICATE");
		long 새id = jdbc.queryForObject("select id from permit_requests where user_id = ? and status = 'PENDING'",
				Long.class, a.getId());
		// 다른 관리자가 번호 X로 직접 승인
		var 관리자2 = com.example.demo.TestLogin.관리자(userRepository, jdbc, "padmin2");
		var 쿠키2 = com.example.demo.TestLogin.관리자_쿠키(tokenService, 관리자2);
		assertThat(승인(쿠키2, 새id, 승인본문(연세id, 번호X), true).getStatus()).isEqualTo(200);
		assertThat(인증학교(a)).isEqualTo(연세id);

		assertThat(철회(a).getStatus()).isEqualTo(204);

		assertThat(REVOKED수(a)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from permit_requests where user_id = ? and status = 'APPROVED'",
				Long.class, a.getId())).isZero();
		var row = jdbc.queryForMap("select verified_school_id, verified_at from users where id = ?", a.getId());
		assertThat(row.get("verified_school_id")).isNull();
		assertThat(row.get("verified_at")).isNull();
	}

	// ---------- 조건 5 ----------
	@Test
	void 취소된_회원_본인이_같은_번호로_재신청해도_자동_승인_안_된다() throws Exception {
		User a = 회원("ra");
		취소된_A(a);
		글자넣기(번호X);

		MockHttpServletResponse r = 업로드(a);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asString()).isEqualTo("PENDING");
		assertThat(기계결과(a)).isEqualTo("NUMBER_DUPLICATE"); // 회원 응답엔 없어 DB 칸으로 확인
		assertThat(인증학교(a)).isNull();
	}

	// ---------- 조건 6 ----------
	@Test
	void 대조군_취소_기록_없는_새_번호는_자동_승인된다() throws Exception {
		취소된_A(회원("ra"));
		User c = 회원("rc");
		글자넣기(번호Y);

		MockHttpServletResponse r = 업로드(c);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asString()).isEqualTo("APPROVED");
		assertThat(인증학교(c)).isEqualTo(연세id);
	}
}
