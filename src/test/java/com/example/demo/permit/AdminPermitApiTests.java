package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

import tools.jackson.databind.JsonNode;

// S20-b 완료 조건 1~5: 관리자 허가서 심사 API (목록·사진·승인·반려)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AdminPermitApiTests extends AdminPermitTestBase {

	// 서버가 쓰는 HMAC 해시(빈)로 기대값을 만든다
	private String sha256(String s) {
		return permitNumberHash.of(s);
	}

	private String 글쓰기JSON(long schoolId) {
		return "{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + schoolId + ",\"category\":\"CHAT\"}";
	}

	private int 글쓰기(User u, long schoolId) throws Exception {
		return mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키(u)).contentType(MediaType.APPLICATION_JSON)
				.content(글쓰기JSON(schoolId))).andReturn().getResponse().getStatus();
	}

	// ================= 조건 1: 목록 =================

	@Test
	void 관리자_목록은_200이고_기본이_PENDING이며_오래된_순이다() throws Exception {
		User a = 회원("pa");
		User b = 회원("pb");
		User c = 회원("pc");
		long ida = 신청(a);
		long idb = 신청(b);
		long idc = 신청(c);
		반려(idb, "BLURRY"); // b는 처리됨 -> 기본 목록에서 빠진다

		MockHttpServletResponse r = 목록(관리자쿠키, "");
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode arr = json(r);
		assertThat(arr.size()).isEqualTo(2);
		assertThat(arr.get(0).get("id").asLong()).isEqualTo(ida);
		assertThat(arr.get(1).get("id").asLong()).isEqualTo(idc);
		assertThat(arr.get(0).get("status").asString()).isEqualTo("PENDING");
		assertThat(arr.get(0).get("nickname").asString()).isEqualTo("pa");
		assertThat(arr.get(0).get("hasPhoto").asBoolean()).isTrue();
		assertThat(ida).isLessThan(idc);
	}

	@Test
	void status로_APPROVED와_REJECTED를_거른다() throws Exception {
		long ida = 신청(회원("pa"));
		long idb = 신청(회원("pb"));
		신청(회원("pc"));
		assertThat(승인(ida, 연세id, "X1").getStatus()).isEqualTo(200);
		assertThat(반려(idb, "OTHER").getStatus()).isEqualTo(200);

		JsonNode ap = json(목록(관리자쿠키, "?status=APPROVED"));
		assertThat(ap.size()).isEqualTo(1);
		assertThat(ap.get(0).get("id").asLong()).isEqualTo(ida);
		assertThat(ap.get(0).get("schoolId").asLong()).isEqualTo(연세id);
		assertThat(ap.get(0).get("schoolName").asString()).isEqualTo("연세대학교");
		assertThat(ap.get(0).get("hasPhoto").asBoolean()).isFalse();

		JsonNode rj = json(목록(관리자쿠키, "?status=REJECTED"));
		assertThat(rj.size()).isEqualTo(1);
		assertThat(rj.get(0).get("id").asLong()).isEqualTo(idb);
		assertThat(rj.get(0).get("rejectReason").asString()).isEqualTo("OTHER");

		assertThat(json(목록(관리자쿠키, "?status=PENDING")).size()).isEqualTo(1);
	}

	@Test
	void 잘못된_status는_400이다() throws Exception {
		assertThat(목록(관리자쿠키, "?status=FOO").getStatus()).isEqualTo(400);
	}

	@Test
	void 목록은_일반회원_403_비로그인_401이다() throws Exception {
		User u = 회원("pu");
		assertThat(목록(쿠키(u), "").getStatus()).isEqualTo(403);
		assertThat(목록(null, "").getStatus()).isEqualTo(401);
	}

	@Test
	void 목록_응답에_key_해시_content_type이_없다() throws Exception {
		User a = 회원("pa");
		long ida = 신청(a);
		long idb = 신청(회원("pb"));
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, ida);
		승인(idb, 연세id, "SECRET-NUM-77");
		String hash = sha256("SECRETNUM77");

		for (String q : List.of("", "?status=APPROVED")) {
			String body = 목록(관리자쿠키, q).getContentAsString(StandardCharsets.UTF_8);
			JsonNode arr = om.readTree(body);
			assertThat(arr.size()).isPositive();
			for (JsonNode item : arr) {
				List<String> names = new ArrayList<>();
				item.propertyNames().forEach(names::add);
				assertThat(names).doesNotContain("photo_key", "photoKey", "permit_number_hash", "permitNumberHash",
						"content_type", "contentType");
			}
			assertThat(body).doesNotContain(key).doesNotContain("permits/").doesNotContain(hash)
					.doesNotContain("SECRETNUM77").doesNotContain("image/jpeg");
		}
	}

	// ================= 조건 2: 사진 보기 =================

	@Test
	void 사진_보기는_올린_사진과_같은_바이트와_헤더를_준다() throws Exception {
		long id = 신청(회원("pa"));
		String key = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, id);
		String type = jdbc.queryForObject("select content_type from permit_requests where id = ?", String.class, id);
		byte[] 저장된 = photoStorage.get(key).orElseThrow();

		MockHttpServletResponse r = 사진(관리자쿠키, id);
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentAsByteArray()).isEqualTo(저장된);
		assertThat(r.getContentType()).isEqualTo(type);
		assertThat(r.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(r.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
	}

	@Test
	void 사진_보기는_일반회원_403_비로그인_401_없는_id_404다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		assertThat(사진(쿠키(u), id).getStatus()).isEqualTo(403);
		assertThat(사진(null, id).getStatus()).isEqualTo(401);
		assertThat(사진(관리자쿠키, 99999999L).getStatus()).isEqualTo(404);
	}

	@Test
	void 처리가_끝난_신청의_사진은_404다() throws Exception {
		long ida = 신청(회원("pa"));
		long idb = 신청(회원("pb"));
		승인(ida, 연세id, "N1");
		반려(idb, "BLURRY");
		assertThat(사진(관리자쿠키, ida).getStatus()).isEqualTo(404);
		assertThat(사진(관리자쿠키, idb).getStatus()).isEqualTo(404);
	}

	// ================= 조건 3: 승인 성공 =================

	@Test
	void 승인하면_신청과_회원이_바뀌고_사진이_사라지고_글쓰기가_열린다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		assertThat(글쓰기(u, 연세id)).as("승인 전").isEqualTo(403);
		assertThat(파일수()).isEqualTo(1);

		MockHttpServletResponse r = 승인(id, 연세id, "AB-12 34 56");
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode body = json(r);
		assertThat(body.get("status").asString()).isEqualTo("APPROVED");
		assertThat(body.get("schoolId").asLong()).isEqualTo(연세id);
		assertThat(body.get("hasPhoto").asBoolean()).isFalse();

		Map<String, Object> row = 신청행(id);
		assertThat(row.get("status")).isEqualTo("APPROVED");
		assertThat(((Number) row.get("school_id")).longValue()).isEqualTo(연세id);
		assertThat(((Number) row.get("decided_by")).longValue()).isEqualTo(관리자.getId());
		assertThat(row.get("decided_at")).isNotNull();
		assertThat((String) row.get("permit_number_hash")).hasSize(64).matches("[0-9a-f]{64}")
				.isEqualTo(sha256("AB123456"));
		assertThat(row.get("photo_key")).isNull();
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();

		// 회원 쪽
		assertThat(json(내상태(u)).get("status").asString()).isEqualTo("APPROVED");
		MockHttpServletResponse me = mockMvc.perform(get("/auth/me").cookie(쿠키(u))).andReturn().getResponse();
		assertThat(me.getStatus()).isEqualTo(200);
		assertThat(json(me).get("verifiedSchool").get("id").asLong()).isEqualTo(연세id);
		assertThat(json(me).get("verifiedSchool").get("name").asString()).isEqualTo("연세대학교");
		assertThat(jdbc.queryForObject("select verified_at is not null from users where id = ?", Boolean.class, u.getId()))
				.isTrue();
		assertThat(jdbc.queryForObject("select school_email_hash from users where id = ?", String.class, u.getId()))
				.isNull();
		assertThat(글쓰기(u, 연세id)).as("승인 후").isEqualTo(201);
	}

	// ================= 조건 4: 승인 실패 =================

	@Test
	void 없는_신청_id로_승인하면_404다() throws Exception {
		assertThat(승인(99999999L, 연세id, "A1").getStatus()).isEqualTo(404);
	}

	@Test
	void 없는_학교로_승인하면_404이고_신청은_그대로다() throws Exception {
		long id = 신청(회원("pa"));
		assertThat(승인(id, 99999999L, "A1").getStatus()).isEqualTo(404);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
		assertThat(파일수()).isEqualTo(1);
	}

	@Test
	void 허가서_번호가_빈_값_공백_하이픈뿐이면_400이다() throws Exception {
		long id = 신청(회원("pa"));
		for (String n : List.of("", "   ", "- -")) {
			assertThat(승인(id, 연세id, n).getStatus()).as("번호 [" + n + "]").isEqualTo(400);
		}
		assertThat(승인(관리자쿠키, id, 승인본문(연세id, null), true).getStatus()).as("번호 없음").isEqualTo(400);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
	}

	@Test
	void schoolId가_없으면_400이다() throws Exception {
		long id = 신청(회원("pa"));
		assertThat(승인(관리자쿠키, id, 승인본문(null, "A1"), true).getStatus()).isEqualTo(400);
	}

	@Test
	void 이미_처리된_신청을_승인하면_409다() throws Exception {
		long ida = 신청(회원("pa"));
		long idb = 신청(회원("pb"));
		assertThat(승인(ida, 연세id, "A1").getStatus()).isEqualTo(200);
		assertThat(승인(ida, 연세id, "A2").getStatus()).isEqualTo(409);
		assertThat(반려(idb, "BLURRY").getStatus()).isEqualTo(200);
		assertThat(승인(idb, 연세id, "A3").getStatus()).isEqualTo(409);
	}

	@Test
	void 신청자가_이미_인증된_회원이면_승인은_409이고_반려는_200이다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		TestLogin.학교_인증_처리(jdbc, "pa", "고려대학교"); // 대기 중에 다른 경로(운영자 SQL 등)로 인증된 상황
		assertThat(승인(id, 연세id, "A1").getStatus()).isEqualTo(409);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
		assertThat(반려(id, "DUPLICATE").getStatus()).isEqualTo(200);
		assertThat(신청행(id).get("status")).isEqualTo("REJECTED");
	}

	@Test
	void 같은_번호가_이미_승인됐으면_409이고_전체가_롤백된다() throws Exception {
		long ida = 신청(회원("pa"));
		User b = 회원("pb");
		long idb = 신청(b);
		assertThat(승인(ida, 연세id, "AB123").getStatus()).isEqualTo(200);
		String keyB = jdbc.queryForObject("select photo_key from permit_requests where id = ?", String.class, idb);

		// 공백·하이픈·대소문자만 다른 같은 번호
		assertThat(승인(idb, 연세id, "ab-12 3").getStatus()).isEqualTo(409);

		assertThat(인증학교(b)).as("회원 미인증").isNull();
		Map<String, Object> row = 신청행(idb);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("permit_number_hash")).isNull();
		assertThat(row.get("photo_key")).isEqualTo(keyB);
		assertThat(photoStorage.get(keyB)).as("사진 그대로").isPresent();
		assertThat(대기표수()).as("지울 파일 대기 표도 롤백").isZero();
		assertThat(json(내상태(b)).get("status").asString()).isEqualTo("PENDING");
	}

	@Test
	void 승인과_반려는_일반회원_403_비로그인_401_CSRF없음_403이다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		String ok = 승인본문(연세id, "A1");
		assertThat(승인(쿠키(u), id, ok, true).getStatus()).isEqualTo(403);
		assertThat(승인(null, id, ok, true).getStatus()).isEqualTo(401);
		assertThat(승인(관리자쿠키, id, ok, false).getStatus()).isEqualTo(403);
		assertThat(반려(쿠키(u), id, "{\"reason\":\"BLURRY\"}", true).getStatus()).isEqualTo(403);
		assertThat(반려(null, id, "{\"reason\":\"BLURRY\"}", true).getStatus()).isEqualTo(401);
		assertThat(반려(관리자쿠키, id, "{\"reason\":\"BLURRY\"}", false).getStatus()).isEqualTo(403);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
	}

	// ================= 조건 5: 반려 =================

	@Test
	void 반려하면_REJECTED가_되고_사진이_지워지고_회원이_다시_신청할_수_있다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);

		MockHttpServletResponse r = 반려(id, "BLURRY");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(json(r).get("status").asString()).isEqualTo("REJECTED");
		assertThat(json(r).get("rejectReason").asString()).isEqualTo("BLURRY");

		Map<String, Object> row = 신청행(id);
		assertThat(row.get("status")).isEqualTo("REJECTED");
		assertThat(row.get("reject_reason")).isEqualTo("BLURRY");
		assertThat(row.get("photo_key")).isNull();
		assertThat(((Number) row.get("decided_by")).longValue()).isEqualTo(관리자.getId());
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
		assertThat(인증학교(u)).isNull();

		JsonNode me = json(내상태(u));
		assertThat(me.get("status").asString()).isEqualTo("REJECTED");
		assertThat(me.get("rejectReason").asString()).isEqualTo("BLURRY");

		long again = 신청(u); // 201로 재신청
		assertThat(again).isNotEqualTo(id);
		assertThat(json(내상태(u)).get("status").asString()).isEqualTo("PENDING");
	}

	@Test
	void 반려_이유가_없거나_목록_밖이면_400이다() throws Exception {
		long id = 신청(회원("pa"));
		assertThat(반려(관리자쿠키, id, "{}", true).getStatus()).isEqualTo(400);
		assertThat(반려(관리자쿠키, id, "{\"reason\":\"FOO\"}", true).getStatus()).isEqualTo(400);
		assertThat(반려(관리자쿠키, id, "{\"reason\":null}", true).getStatus()).isEqualTo(400);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
	}

	@Test
	void 없는_신청을_반려하면_404다() throws Exception {
		assertThat(반려(99999999L, "BLURRY").getStatus()).isEqualTo(404);
	}

	@Test
	void 이미_처리된_신청을_반려하면_409다() throws Exception {
		long ida = 신청(회원("pa"));
		long idb = 신청(회원("pb"));
		반려(ida, "BLURRY");
		승인(idb, 연세id, "A1");
		assertThat(반려(ida, "OTHER").getStatus()).isEqualTo(409);
		assertThat(반려(idb, "OTHER").getStatus()).isEqualTo(409);
		assertThat(신청행(ida).get("reject_reason")).isEqualTo("BLURRY");
	}
}
