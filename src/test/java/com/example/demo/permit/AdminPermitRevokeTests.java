package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

import jakarta.servlet.http.Cookie;

// S21-a 관리자 인증 취소 API(PUT /admin/permit-requests/{id}/revoke) 검사. 값은 전부 지어낸 것.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakePermitTextReader.Config.class })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AdminPermitRevokeTests extends AdminPermitTestBase {

	@Autowired FakePermitTextReader 판독기;

	// 가짜 판독기는 기본이 "못 읽음"이라 신청은 PENDING으로 남는다(수동 심사 흐름). 자동 승인 시험에서만 글자를 넣는다.
	@BeforeEach
	void 판독기초기화() {
		판독기.reset();
	}

	private MockHttpServletResponse 취소(Cookie c, long id, String json, boolean withCsrf) throws Exception {
		var b = put(ADMIN_URL + "/" + id + "/revoke").contentType(MediaType.APPLICATION_JSON).content(json);
		if (withCsrf) {
			b.with(csrf());
		}
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private MockHttpServletResponse 취소(long id, String reason) throws Exception {
		return 취소(관리자쿠키, id, "{\"reason\":\"" + reason + "\"}", true);
	}

	// 회원 신청 -> 관리자 승인까지 해서 APPROVED 신청 id를 돌려준다
	private long 승인된_신청(User u, String number) throws Exception {
		long id = 신청(u);
		assertThat(승인(id, 연세id, number).getStatus()).isEqualTo(200);
		return id;
	}

	private String 글쓰기본문() {
		return "{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 연세id + ",\"category\":\"CHAT\"}";
	}

	private int 글쓰기(User u) throws Exception {
		return mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키(u)).contentType(MediaType.APPLICATION_JSON)
				.content(글쓰기본문())).andReturn().getResponse().getStatus();
	}

	// ---------- 조건 1 ----------
	@Test
	void 승인된_신청을_취소하면_200이고_status_REVOKED_rejectReason_NOT_PERMIT이다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-1");

		MockHttpServletResponse r = 취소(id, "NOT_PERMIT");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(json(r).get("status").asString()).isEqualTo("REVOKED");
		assertThat(json(r).get("rejectReason").asString()).isEqualTo("NOT_PERMIT");
		// DB에도 취소 사유와 결정자가 남는다
		assertThat(신청행(id).get("status")).isEqualTo("REVOKED");
		assertThat(신청행(id).get("reject_reason")).isEqualTo("NOT_PERMIT");
		assertThat(((Number) 신청행(id).get("decided_by")).longValue()).isEqualTo(관리자.getId());
	}

	// ---------- 조건 2 ----------
	@Test
	void 취소하면_회원의_인증_칸이_비고_글쓰기는_403이다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-2");
		assertThat(글쓰기(u)).as("취소 전에는 글쓰기 가능").isEqualTo(201);

		assertThat(취소(id, "NOT_PERMIT").getStatus()).isEqualTo(200);

		var row = jdbc.queryForMap("select verified_school_id, verified_at from users where id = ?", u.getId());
		assertThat(row.get("verified_school_id")).isNull();
		assertThat(row.get("verified_at")).isNull();
		assertThat(글쓰기(u)).isEqualTo(403);
	}

	// ---------- 조건 3 ----------
	@Test
	void 취소된_회원의_내_상태는_200_REVOKED이고_사유가_보인다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-3");
		취소(id, "NOT_PERMIT");

		MockHttpServletResponse r = 내상태(u);

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(json(r).get("status").asString()).isEqualTo("REVOKED");
		assertThat(json(r).get("rejectReason").asString()).isEqualTo("NOT_PERMIT");
	}

	// ---------- 조건 4 ----------
	@Test
	void 없는_id를_취소하면_404다() throws Exception {
		assertThat(취소(999999L, "NOT_PERMIT").getStatus()).isEqualTo(404);
	}

	@Test
	void 대기_신청을_취소하면_409이고_그대로다() throws Exception {
		long id = 신청(회원("ra"));
		assertThat(취소(id, "NOT_PERMIT").getStatus()).isEqualTo(409);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
	}

	@Test
	void 반려된_신청을_취소하면_409다() throws Exception {
		long id = 신청(회원("ra"));
		assertThat(반려(id, "BLURRY").getStatus()).isEqualTo(200);
		assertThat(취소(id, "NOT_PERMIT").getStatus()).isEqualTo(409);
		assertThat(신청행(id).get("status")).isEqualTo("REJECTED");
		assertThat(신청행(id).get("reject_reason")).isEqualTo("BLURRY");
	}

	@Test
	void 이미_취소된_신청을_다시_취소하면_409다() throws Exception {
		long id = 승인된_신청(회원("ra"), "RV-4");
		assertThat(취소(id, "NOT_PERMIT").getStatus()).isEqualTo(200);
		assertThat(취소(id, "BLURRY").getStatus()).isEqualTo(409);
		assertThat(신청행(id).get("reject_reason")).as("두 번째 요청은 사유를 덮어쓰지 않는다").isEqualTo("NOT_PERMIT");
	}

	// ---------- 조건 5 ----------
	@Test
	void reason_없음_목록_밖_값_EXPIRED는_400이고_승인_상태가_유지된다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-5");

		assertThat(취소(관리자쿠키, id, "{}", true).getStatus()).as("reason 없음").isEqualTo(400);
		assertThat(취소(id, "NO_SUCH_REASON").getStatus()).as("목록 밖 값").isEqualTo(400);
		assertThat(취소(id, "EXPIRED").getStatus()).as("EXPIRED").isEqualTo(400);

		assertThat(신청행(id).get("status")).isEqualTo("APPROVED");
		assertThat(인증학교(u)).isNotNull();
	}

	@Test
	void 관리자가_아니면_403이고_미로그인은_401이다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-6");
		String body = "{\"reason\":\"NOT_PERMIT\"}";

		assertThat(취소(쿠키(회원("rother")), id, body, true).getStatus()).as("일반 회원").isEqualTo(403);
		assertThat(취소(null, id, body, true).getStatus()).as("미로그인").isEqualTo(401);

		assertThat(신청행(id).get("status")).isEqualTo("APPROVED");
		assertThat(인증학교(u)).isNotNull();
	}

	// ---------- 조건 6 ----------
	@Test
	void 관리자_본인_승인_건을_본인이_취소하면_403이다() throws Exception {
		// 관리자가 신청하고 다른 관리자가 승인한 뒤, 본인이 취소 시도
		long id = 신청(관리자);
		User admin2 = TestLogin.관리자(userRepository, jdbc, "padmin2");
		Cookie c2 = TestLogin.관리자_쿠키(tokenService, admin2);
		assertThat(승인(c2, id, 승인본문(연세id, "RV-7"), true).getStatus()).isEqualTo(200);

		assertThat(취소(id, "NOT_PERMIT").getStatus()).isEqualTo(403);

		assertThat(신청행(id).get("status")).isEqualTo("APPROVED");
		assertThat(인증학교(관리자)).isNotNull();
	}

	// ---------- 조건 7 ----------
	@Test
	void 목록_status_REVOKED는_200이고_취소_건만_담는다() throws Exception {
		long 취소건 = 승인된_신청(회원("ra"), "RV-8");
		승인된_신청(회원("rb"), "RV-9"); // 취소하지 않은 승인 건: 목록에 없어야 한다
		취소(취소건, "NOT_PERMIT");

		MockHttpServletResponse r = 목록(관리자쿠키, "?status=REVOKED");

		assertThat(r.getStatus()).isEqualTo(200);
		var arr = json(r);
		assertThat(arr.size()).isEqualTo(1);
		assertThat(arr.get(0).get("id").asLong()).isEqualTo(취소건);
		assertThat(arr.get(0).get("status").asString()).isEqualTo("REVOKED");
	}

	// ---------- 조건 8 ----------
	@Test
	void 취소와_본인_철회가_동시에_와도_500_없이_끝난다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-10");
		Cookie 회원쿠키 = 쿠키(u);

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()), List.of(
				() -> 취소(id, "NOT_PERMIT").getStatus(),
				() -> mockMvc.perform(delete(ME_URL).cookie(회원쿠키).with(csrf())).andReturn().getResponse().getStatus()));

		assertThat(res).as("교착(500) 없음").doesNotContain(500);
		assertThat(res.get(0)).as("취소는 200 또는 404(철회가 먼저)").isIn(200, 404);
		assertThat(res.get(1)).as("철회는 항상 204").isEqualTo(204);
		// 어느 쪽이 먼저든 회원의 인증은 풀려 있다
		assertThat(인증학교(u)).isNull();
	}

	// ---------- 조건 9 ----------
	@Test
	void 취소된_회원은_다시_신청할_수_있다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-11");
		취소(id, "NOT_PERMIT");

		MockHttpServletResponse r = mockMvc.perform(multipart(ME_URL)
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림())).param("consent", "true")
				.cookie(쿠키(u)).with(csrf())).andReturn().getResponse();

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(내상태(u)).get("status").asString()).isEqualTo("PENDING");
	}

	// ---------- S21-a review 보강 ----------

	// a) CSRF 토큰 없이 보내면 막힌다
	@Test
	void CSRF_없이_취소하면_403이고_승인_상태가_유지된다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-12");

		assertThat(취소(관리자쿠키, id, "{\"reason\":\"NOT_PERMIT\"}", false).getStatus()).isEqualTo(403);

		assertThat(신청행(id).get("status")).isEqualTo("APPROVED");
		assertThat(인증학교(u)).isNotNull();
	}

	// b) decided_at은 승인 시각이 아니라 취소 시각으로 덮어써진다
	@Test
	void 취소하면_decided_at이_취소_시각으로_덮어써진다() throws Exception {
		long id = 승인된_신청(회원("ra"), "RV-13");
		var 승인시각 = ((java.sql.Timestamp) 신청행(id).get("decided_at")).toInstant();

		MockHttpServletResponse r = 취소(id, "NOT_PERMIT");

		var 취소시각 = ((java.sql.Timestamp) 신청행(id).get("decided_at")).toInstant();
		assertThat(취소시각).isAfter(승인시각);
		assertThat(java.time.Instant.parse(json(r).get("decidedAt").asString())).isEqualTo(취소시각);
	}

	// c) 자동 승인 건(decided_by NULL, auto_decided true)도 취소된다
	@Test
	void 자동_승인_건을_취소하면_200이고_decided_by가_취소_관리자이고_인증이_비워진다() throws Exception {
		판독기.text = String.join("\n", "표준입학허가서", "국적(Country of citizenship): 베트남", "학교명(School name): 연세대학교",
				"표준입학허가서 번호(Certificate of Admission No.): 제 2013-0001 호");
		User u = 회원("rauto");
		assertThat(mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse().getStatus()).isEqualTo(201);
		long id = jdbc.queryForObject("select id from permit_requests where user_id = ?", Long.class, u.getId());
		assertThat(신청행(id).get("auto_decided")).isEqualTo(true);
		assertThat(신청행(id).get("decided_by")).isNull();
		assertThat(인증학교(u)).isNotNull();

		MockHttpServletResponse r = 취소(id, "NOT_PERMIT");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(json(r).get("status").asString()).isEqualTo("REVOKED");
		assertThat(((Number) 신청행(id).get("decided_by")).longValue()).isEqualTo(관리자.getId());
		assertThat(인증학교(u)).isNull();
	}

	// d) 관리자 둘이 같은 건을 동시에 취소하면 한 명만 성공
	@Test
	void 관리자_둘이_같은_건을_동시에_취소하면_200_하나와_409_하나다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-14");
		User admin2 = TestLogin.관리자(userRepository, jdbc, "padmin2");
		Cookie c2 = TestLogin.관리자_쿠키(tokenService, admin2);
		String body = "{\"reason\":\"NOT_PERMIT\"}";

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()), List.of(
				() -> 취소(관리자쿠키, id, body, true).getStatus(),
				() -> 취소(c2, id, body, true).getStatus()));

		assertThat(res).containsExactlyInAnyOrder(200, 409);
		assertThat(신청행(id).get("status")).isEqualTo("REVOKED");
		assertThat(인증학교(u)).isNull();
	}

	// e) 취소와 회원 탈퇴가 겹쳐도 교착(500)이 없다
	@Test
	void 취소와_회원_탈퇴가_동시에_와도_500_없이_끝난다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-15");
		Cookie 회원쿠키 = 쿠키(u);

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()), List.of(
				() -> 취소(id, "NOT_PERMIT").getStatus(),
				() -> mockMvc.perform(delete("/users/me").with(csrf()).cookie(회원쿠키).contentType(MediaType.APPLICATION_JSON)
						.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus()));

		assertThat(res).doesNotContain(500);
		assertThat(res.get(0)).as("취소는 200 또는 404(탈퇴가 먼저)").isIn(200, 404);
		assertThat(res.get(1)).as("탈퇴는 정상").isEqualTo(204);
		assertThat(userRepository.findById(u.getId())).isEmpty();
		assertThat(행수()).isZero();
	}

	// f) 응답 값과, clearVerification이 비우는 3칸(verified_school_id, verified_at, school_email_hash)
	@Test
	void 취소_응답은_원래_학교_이름과_hasPhoto_false이고_회원의_인증_3칸이_모두_빈다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-16");
		jdbc.update("update users set school_email_hash = ? where id = ?", "a".repeat(64), u.getId()); // 예전 메일 인증 흔적

		MockHttpServletResponse r = 취소(id, "NOT_PERMIT");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(json(r).get("schoolName").asString()).isEqualTo("연세대학교");
		assertThat(json(r).get("hasPhoto").asBoolean()).isFalse();
		var row = jdbc.queryForMap("select verified_school_id, verified_at, school_email_hash from users where id = ?", u.getId());
		assertThat(row.values()).hasSize(3).allMatch(v -> v == null);
	}

	// g) 재신청해도 취소 기록(REVOKED 줄)은 남는다
	@Test
	void 재신청_뒤에도_REVOKED_줄이_DB에_남아_있다() throws Exception {
		User u = 회원("ra");
		long id = 승인된_신청(u, "RV-17");
		취소(id, "NOT_PERMIT");

		long 새id = 신청(u);

		assertThat(새id).isNotEqualTo(id);
		assertThat(신청행(id).get("status")).isEqualTo("REVOKED");
		assertThat(신청행(id).get("reject_reason")).isEqualTo("NOT_PERMIT");
		assertThat(jdbc.queryForObject("select count(*) from permit_requests where user_id = ?", Long.class, u.getId()))
				.isEqualTo(2);
	}

	// h) 대기 건 취소 409의 안내 문구
	@Test
	void 대기_건_취소_409의_메시지는_승인된_신청만_취소할_수_있다이다() throws Exception {
		long id = 신청(회원("ra"));

		MockHttpServletResponse r = 취소(id, "NOT_PERMIT");

		assertThat(r.getStatus()).isEqualTo(409);
		// MockMvc는 사유를 sendError 메시지나 본문으로 싣는다. 둘 중 어디든 문구가 있으면 된다.
		assertThat(r.getErrorMessage() + r.getContentAsString()).contains("승인된 신청만 취소할 수 있습니다.");
	}

	// i) 로그에는 adminId·requestId만, 번호·해시·사진 key는 없다
	@Test
	void 취소_로그에는_adminId와_requestId만_있고_번호_해시_key가_없다(CapturedOutput output) throws Exception {
		String 번호원문 = "ZQ-98 76 54";
		String 번호정규화 = "ZQ987654";
		User u = 회원("ra");
		long id = 신청(u);
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, id);
		assertThat(승인(id, 연세id, 번호원문).getStatus()).isEqualTo(200);

		assertThat(취소(id, "NOT_PERMIT").getStatus()).isEqualTo(200);

		String uuid = key.substring(key.lastIndexOf('/') + 1, key.lastIndexOf('.'));
		assertThat(output.getAll()).contains("허가서 인증 취소: adminId=" + 관리자.getId() + ", requestId=" + id)
				.doesNotContain(key).doesNotContain(uuid).doesNotContain(번호원문).doesNotContain(번호정규화)
				.doesNotContain(permitNumberHash.of(번호정규화));
	}
}
