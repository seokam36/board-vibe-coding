package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.FileSystemUtils;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

// S20-b review 수정 검사: 목록 정렬, 본인 심사 403, 로그 민감값, 관리자 DB 재확인, 사진 없는 신청
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class AdminPermitReviewFixTests extends AdminPermitTestBase {

	static final String 번호원문 = "LEAK-77 88 99";
	static final String 번호정규화 = "LEAK778899";

	private void 로그에_민감값이_없다(CapturedOutput output) {
		String 해시 = permitNumberHash.of(번호정규화);
		assertThat(output.getAll()).doesNotContain(해시).doesNotContain(번호원문).doesNotContain(번호정규화)
				.doesNotContain("Key (permit_number_hash)").doesNotContain("Detail").doesNotContain("already exists");
	}

	// ---------- 4. 정렬 ----------

	@Test
	void PENDING은_오래된_순이고_APPROVED_REJECTED는_최신순이다() throws Exception {
		long a = 신청(회원("pa"));
		long b = 신청(회원("pb"));
		long c = 신청(회원("pc"));
		long d = 신청(회원("pd"));
		long e = 신청(회원("pe"));
		long f = 신청(회원("pf"));
		승인(a, 연세id, "S1");
		승인(b, 연세id, "S2");
		승인(c, 연세id, "S3");
		반려(d, "BLURRY");
		반려(e, "BLURRY");

		JsonNode ap = json(목록(관리자쿠키, "?status=APPROVED"));
		assertThat(ap.get(0).get("id").asLong()).isEqualTo(c);
		assertThat(ap.get(1).get("id").asLong()).isEqualTo(b);
		assertThat(ap.get(2).get("id").asLong()).isEqualTo(a);
		JsonNode rj = json(목록(관리자쿠키, "?status=REJECTED"));
		assertThat(rj.get(0).get("id").asLong()).isEqualTo(e);
		assertThat(rj.get(1).get("id").asLong()).isEqualTo(d);

		long g = 신청(회원("pg"));
		JsonNode pe = json(목록(관리자쿠키, ""));
		assertThat(pe.get(0).get("id").asLong()).isEqualTo(f);
		assertThat(pe.get(1).get("id").asLong()).isEqualTo(g);
	}

	// ---------- 5. 본인 신청 ----------

	@Test
	void 본인_신청을_본인이_승인_반려하면_403이고_신청은_PENDING_그대로다() throws Exception {
		long id = 신청(관리자);
		assertThat(승인(id, 연세id, "SELF1").getStatus()).isEqualTo(403);
		assertThat(반려(id, "BLURRY").getStatus()).isEqualTo(403);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
		assertThat(신청행(id).get("photo_key")).isNotNull();
		assertThat(인증학교(관리자)).isNull();
		assertThat(대기표수()).isZero();
	}

	@Test
	void 다른_관리자는_그_관리자의_신청을_승인할_수_있다() throws Exception {
		long id = 신청(관리자);
		User other = TestLogin.관리자(userRepository, jdbc, "padmin2");
		MockHttpServletResponse r = 승인(TestLogin.관리자_쿠키(tokenService, other), id, 승인본문(연세id, "SELF2"), true);
		assertThat(r.getStatus()).isEqualTo(200);
	}

	// ---------- 6. 이미 승인된 번호 로그 ----------

	@Test
	void 이미_승인된_번호로_승인하면_409이고_로그에_해시_번호_Detail이_없다(CapturedOutput output) throws Exception {
		long a = 신청(회원("pa"));
		long b = 신청(회원("pb"));
		assertThat(승인(a, 연세id, 번호원문).getStatus()).isEqualTo(200);
		assertThat(승인(b, 연세id, 번호원문.toLowerCase()).getStatus()).isEqualTo(409);
		assertThat(신청행(b).get("status")).isEqualTo("PENDING");
		로그에_민감값이_없다(output);
	}

	// ---------- 7. 동시 경쟁(UNIQUE 위반 경로) 로그 ----------

	@Test
	void 같은_번호_동시_승인_경쟁에서도_로그에_해시와_Detail이_없다(CapturedOutput output) throws Exception {
		User a = 회원("pa");
		User b = 회원("pb");
		long ida = 신청(a);
		long idb = 신청(b);
		Cookie c2 = TestLogin.관리자_쿠키(tokenService, TestLogin.관리자(userRepository, jdbc, "padmin2"));
		List<Callable<Integer>> calls = List.of(() -> 승인(관리자쿠키, ida, 승인본문(연세id, 번호원문), true).getStatus(),
				() -> 승인(c2, idb, 승인본문(연세id, 번호원문), true).getStatus());

		List<Integer> res = 잠가놓고_동시에(List.of(a.getId(), b.getId()), calls);

		assertThat(res).containsExactlyInAnyOrder(200, 409);
		로그에_민감값이_없다(output);
	}

	// ---------- 8. 검증 실패 로그 ----------

	@Test
	void 승인_검증_실패는_400이고_로그에_번호_원문이_없다(CapturedOutput output) throws Exception {
		long id = 신청(회원("pa"));
		String 긴번호 = "LEAKLONGVALUE" + "9".repeat(45); // 51자 이상
		assertThat(긴번호.length()).isGreaterThan(50);
		assertThat(승인(관리자쿠키, id, 승인본문(연세id, 긴번호), true).getStatus()).isEqualTo(400);
		assertThat(승인(관리자쿠키, id, 승인본문(null, "LEAKNOSCHOOL123"), true).getStatus()).isEqualTo(400);
		assertThat(output.getAll()).doesNotContain(긴번호).doesNotContain("LEAKLONGVALUE").doesNotContain("LEAKNOSCHOOL123");
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
	}

	// ---------- 9. DB 재확인 ----------

	@Test
	void 토큰은_ADMIN인데_DB에서_강등되면_4개_API_모두_403이다() throws Exception {
		long id = 신청(회원("pa"));
		jdbc.update("update users set role = 'USER' where id = ?", 관리자.getId());
		assertThat(목록(관리자쿠키, "").getStatus()).isEqualTo(403);
		assertThat(사진(관리자쿠키, id).getStatus()).isEqualTo(403);
		assertThat(승인(id, 연세id, "D1").getStatus()).isEqualTo(403);
		assertThat(반려(id, "BLURRY").getStatus()).isEqualTo(403);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
	}

	@Test
	void 관리자가_탈퇴해_DB에_없으면_4개_API_모두_401이다() throws Exception {
		long id = 신청(회원("pa"));
		jdbc.update("delete from users where id = ?", 관리자.getId());
		assertThat(목록(관리자쿠키, "").getStatus()).isEqualTo(401);
		assertThat(사진(관리자쿠키, id).getStatus()).isEqualTo(401);
		assertThat(승인(id, 연세id, "D1").getStatus()).isEqualTo(401);
		assertThat(반려(id, "BLURRY").getStatus()).isEqualTo(401);
		assertThat(신청행(id).get("status")).isEqualTo("PENDING");
	}

	// ---------- 10. 사진 없는 신청 ----------

	@Test
	void photo_key는_있는데_창고에_파일이_없으면_사진은_404이고_목록은_hasPhoto_true이고_승인_반려는_200이다() throws Exception {
		long ida = 신청(회원("pa"));
		long idb = 신청(회원("pb"));
		FileSystemUtils.deleteRecursively(Path.of(uploadDir)); // 파일만 사라진 상황

		assertThat(사진(관리자쿠키, ida).getStatus()).isEqualTo(404);
		JsonNode arr = json(목록(관리자쿠키, ""));
		assertThat(arr.size()).isEqualTo(2);
		assertThat(arr.get(0).get("hasPhoto").asBoolean()).isTrue();
		assertThat(승인(ida, 연세id, "NF1").getStatus()).isEqualTo(200);
		assertThat(반려(idb, "BLURRY").getStatus()).isEqualTo(200);
		assertThat(신청행(ida).get("status")).isEqualTo("APPROVED");
		assertThat(신청행(idb).get("status")).isEqualTo("REJECTED");
	}

	@Test
	void photo_key가_NULL인_PENDING도_승인_반려가_200이다() throws Exception {
		long ida = 신청(회원("pa"));
		long idb = 신청(회원("pb"));
		jdbc.update("update permit_requests set photo_key = null, content_type = null");

		assertThat(사진(관리자쿠키, ida).getStatus()).isEqualTo(404);
		assertThat(json(목록(관리자쿠키, "")).get(0).get("hasPhoto").asBoolean()).isFalse();
		assertThat(승인(ida, 연세id, "NK1").getStatus()).isEqualTo(200);
		assertThat(반려(idb, "OTHER").getStatus()).isEqualTo(200);
	}
}
