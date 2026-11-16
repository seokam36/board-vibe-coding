package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

import tools.jackson.databind.JsonNode;

// S20-d2 완료 조건: 신청에 판정을 연결하고, 4가지가 다 맞으면 자동 승인한다.
// 글자는 가짜 판독기가 정해 준다. 번호·이름은 모두 지어낸 값이고, 학교는 별칭 표(V14)에 있는 연세·고려를 쓴다.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakePermitTextReader.Config.class })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PermitAutoReviewApiTests extends AdminPermitTestBase {

	@Autowired FakePermitTextReader 판독기;

	static final String 번호원문 = "제 2013-0001 호";
	static final String 번호정규화 = "20130001";

	@BeforeEach
	void 판독기초기화() {
		판독기.reset();
	}

	// 4가지(제목·국적·학교·번호)가 모두 맞는 글자
	private static String 완벽(String 학교, String 번호) {
		return String.join("\n", "표준입학허가서", "국적(Country of citizenship): 베트남", "학교명(School name): " + 학교,
				"표준입학허가서 번호(Certificate of Admission No.): " + 번호);
	}

	private MockHttpServletResponse 제출(User u) throws Exception {
		return mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
	}

	private Map<String, Object> 최근행(User u) {
		return jdbc.queryForMap("select * from permit_requests where user_id = ? order by id desc limit 1", u.getId());
	}

	private String 오늘횟수(User u) {
		return redis.opsForValue().get("permit-upload-day:" + u.getId() + ":" + LocalDate.now(ZoneId.of("Asia/Seoul")));
	}

	// ---- 1. 자동 승인 ----

	@Test
	void 네_가지가_모두_맞으면_자동_승인된다() throws Exception {
		판독기.text = 완벽("연세대학교", 번호원문);
		User u = 회원("auto1");

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		JsonNode body = json(r);
		assertThat(body.get("status").asText()).isEqualTo("APPROVED");
		assertThat(body.get("requestedAt").isNull()).isFalse();
		assertThat(body.get("decidedAt").isNull()).isFalse();
		assertThat(body.has("machineResult")).isFalse();

		Map<String, Object> row = 최근행(u);
		assertThat(row.get("status")).isEqualTo("APPROVED");
		assertThat(row.get("auto_decided")).isEqualTo(true);
		assertThat(row.get("decided_by")).isNull();
		assertThat(row.get("machine_result")).isEqualTo("ALL_PASSED");
		assertThat(row.get("school_id")).isEqualTo(연세id);
		assertThat(row.get("detected_school_id")).isEqualTo(연세id);
		assertThat(row.get("photo_key")).isNull();
		assertThat(row.get("content_type")).isNull();
		assertThat(row.get("permit_number_hash")).isEqualTo(permitNumberHash.of(번호정규화));
		assertThat(인증학교(u)).isEqualTo(연세id);
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	// ---- 7. 응답·목록 ----

	@Test
	void 자동_승인_건은_관리자_목록과_내_상태에_보인다() throws Exception {
		판독기.text = 완벽("연세대학교", 번호원문);
		User u = 회원("auto7");
		제출(u);

		JsonNode list = json(목록(관리자쿠키, "?status=APPROVED"));
		assertThat(list).hasSize(1);
		assertThat(list.get(0).get("autoDecided").asBoolean()).isTrue();
		assertThat(list.get(0).get("machineResult").asText()).isEqualTo("ALL_PASSED");
		assertThat(list.get(0).get("schoolName").asText()).isEqualTo("연세대학교");
		assertThat(list.get(0).get("hasPhoto").asBoolean()).isFalse();

		MockHttpServletResponse me = 내상태(u);
		assertThat(me.getStatus()).isEqualTo(200);
		JsonNode meBody = json(me);
		assertThat(meBody.get("status").asText()).isEqualTo("APPROVED");
		assertThat(meBody.has("machineResult")).isFalse();
		assertThat(meBody.get("decidedAt").isNull()).isFalse();
	}

	@Test
	void 대기_건은_관리자_목록에_판정_코드와_감지한_학교가_보인다() throws Exception {
		판독기.text = String.join("\n", "표준입학허가서", "국적(Country of citizenship): 대한민국", "학교명(School name): 고려대학교",
				"표준입학허가서 번호(Certificate of Admission No.): 99887766");
		User u = 회원("pend7");
		제출(u);

		JsonNode list = json(목록(관리자쿠키, "?status=PENDING"));
		assertThat(list).hasSize(1);
		assertThat(list.get(0).get("machineResult").asText()).isEqualTo("NATIONALITY_UNCLEAR");
		assertThat(list.get(0).get("detectedSchoolName").asText()).isEqualTo("고려대학교");
		assertThat(list.get(0).get("autoDecided").asBoolean()).isFalse();
		assertThat(list.get(0).get("hasPhoto").asBoolean()).isTrue();
		assertThat(json(내상태(u)).get("status").asText()).isEqualTo("PENDING");
	}

	// ---- 2. 못 읽음 ----

	@Test
	void 판독기가_빈_값이나_공백이면_UNREADABLE로_대기한다() throws Exception {
		for (String 글자 : new String[] { null, "", "  \n " }) {
			기본정리();
			판독기.reset();
			판독기.text = 글자;
			User u = 회원("unread" + (글자 == null ? "n" : 글자.length()));

			MockHttpServletResponse r = 제출(u);

			assertThat(r.getStatus()).isEqualTo(201);
			assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
			Map<String, Object> row = 최근행(u);
			assertThat(row.get("status")).isEqualTo("PENDING");
			assertThat(row.get("machine_result")).isEqualTo("UNREADABLE");
			assertThat(row.get("auto_decided")).isEqualTo(false);
			assertThat(row.get("permit_number_hash")).isNull();
			assertThat(row.get("photo_key")).isNotNull();
			assertThat(파일수()).isEqualTo(1);
			assertThat(오늘횟수(u)).isEqualTo("1");
		}
	}

	@Test
	void 판독기_예외도_UNREADABLE로_대기하고_5xx가_아니다() throws Exception {
		판독기.toThrow = new IllegalStateException("판독기비밀메시지 permits/x");
		User u = 회원("boom");

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		Map<String, Object> row = 최근행(u);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("machine_result")).isEqualTo("UNREADABLE");
		assertThat(파일수()).isEqualTo(1);
		assertThat(오늘횟수(u)).isEqualTo("1");
	}

	// ---- 3. 한 가지만 틀림 ----

	@Test
	void 별칭에_없는_학교만_틀리면_SCHOOL_NOT_MATCHED로_대기한다() throws Exception {
		판독기.text = 완벽("없는대학교", 번호원문);
		User u = 회원("school");

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		Map<String, Object> row = 최근행(u);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("machine_result")).isEqualTo("SCHOOL_NOT_MATCHED");
		assertThat(row.get("detected_school_id")).isNull();
		assertThat(row.get("photo_key")).isNotNull();
		assertThat(인증학교(u)).isNull();
	}

	@Test
	void 국적만_틀리면_학교는_감지된_채_대기하고_반려_행은_안_생긴다() throws Exception {
		판독기.text = String.join("\n", "표준입학허가서", "국적(Country of citizenship): 대한민국", "학교명(School name): 연세대학교",
				"표준입학허가서 번호(Certificate of Admission No.): 11223344");
		User u = 회원("nation");
		제출(u);
		Map<String, Object> row = 최근행(u);
		assertThat(row.get("machine_result")).isEqualTo("NATIONALITY_UNCLEAR");
		assertThat(row.get("detected_school_id")).isEqualTo(연세id);

		// 제목 없음·번호 없음도 PENDING으로만 남는다
		User v = 회원("notitle");
		판독기.text = "무슨 증명서\n학교명(School name): 연세대학교";
		제출(v);
		assertThat(최근행(v).get("machine_result")).isEqualTo("NO_TITLE");
		User w = 회원("nonum");
		판독기.text = "표준입학허가서\n국적(Country of citizenship): 베트남\n학교명(School name): 연세대학교";
		제출(w);
		assertThat(최근행(w).get("machine_result")).isEqualTo("NUMBER_NOT_FOUND");

		assertThat(jdbc.queryForObject("select count(*) from permit_requests where status = 'REJECTED'", Long.class)).isZero();
	}

	// ---- 4·5. 같은 번호 ----

	@Test
	void 같은_번호가_이미_승인돼_있으면_NUMBER_DUPLICATE로_대기한다() throws Exception {
		판독기.text = 완벽("연세대학교", 번호원문);
		User 첫째 = 회원("dup1");
		assertThat(json(제출(첫째)).get("status").asText()).isEqualTo("APPROVED");

		User 둘째 = 회원("dup2");
		MockHttpServletResponse r = 제출(둘째);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		Map<String, Object> row = 최근행(둘째);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("machine_result")).isEqualTo("NUMBER_DUPLICATE");
		assertThat(row.get("detected_school_id")).isEqualTo(연세id);
		assertThat(row.get("permit_number_hash")).isNull();
		assertThat(row.get("photo_key")).isNotNull();
		assertThat(인증학교(둘째)).isNull();
	}

	@Test
	void 기계가_승인한_번호를_관리자가_다른_회원에게_승인하면_409() throws Exception {
		판독기.text = 완벽("연세대학교", 번호원문);
		User 첫째 = 회원("adm1");
		assertThat(json(제출(첫째)).get("status").asText()).isEqualTo("APPROVED");

		판독기.text = null; // 둘째는 기계가 못 읽어 관리자 대기
		User 둘째 = 회원("adm2");
		long id = 신청(둘째);

		MockHttpServletResponse r = 승인(id, 연세id, "2013-0001");

		assertThat(r.getStatus()).isEqualTo(409);
		assertThat(최근행(둘째).get("status")).isEqualTo("PENDING");
		assertThat(인증학교(둘째)).isNull();
	}

	// ---- 6. 판독 도중 사정 변경 ----

	@Test
	void 판독_도중_회원이_철회하면_조용히_끝나고_201이다() throws Exception {
		User u = 회원("withdraw");
		판독기.text = 완벽("연세대학교", 번호원문);
		판독기.onRead = () -> {
			try {
				// 다른 스레드에서 철회(DELETE 46번)를 실행하고 끝나기를 기다린다
				CompletableFuture.runAsync(() -> {
					try {
						int status = mockMvc.perform(delete(ME_URL).cookie(쿠키(u)).with(csrf())).andReturn().getResponse().getStatus();
						assertThat(status).isEqualTo(204);
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				}).get(15, TimeUnit.SECONDS);
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		};

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING"); // INSERT 시점의 응답
		assertThat(행수()).isZero();
		assertThat(인증학교(u)).isNull();
		assertThat(파일수()).isZero();
		assertThat(대기표수()).isZero();
	}

	@Test
	void 판독_도중_다른_경로로_이미_인증됐으면_자동_승인하지_않고_대기로_남는다() throws Exception {
		User u = 회원("already");
		판독기.text = 완벽("연세대학교", 번호원문);
		판독기.onRead = () -> jdbc.update("update users set verified_school_id = ?, verified_at = now() where id = ?", 고려id, u.getId());

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		Map<String, Object> row = 최근행(u);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("auto_decided")).isEqualTo(false);
		assertThat(row.get("permit_number_hash")).isNull();
		assertThat(인증학교(u)).isEqualTo(고려id); // 덮어쓰지 않는다
	}

	// ---- 8. 로그 ----

	@Test
	void 자동_승인_로그에는_글자_번호_해시_key가_없다(CapturedOutput output) throws Exception {
		판독기.text = 완벽("연세대학교", 번호원문);
		User u = 회원("log1");
		제출(u);

		String log = output.getAll();
		assertThat(log).contains("허가서 자동 승인");
		assertThat(log).doesNotContain("2013-0001", 번호정규화, permitNumberHash.of(번호정규화), "permits/", "Country of citizenship",
				"베트남");
	}

	@Test
	void 판정_실패와_판독기_예외_로그에는_민감값이_없고_예외는_클래스_이름만(CapturedOutput output) throws Exception {
		판독기.text = 완벽("없는대학교", 번호원문);
		제출(회원("log2"));
		판독기.toThrow = new IllegalStateException("판독기비밀메시지 permits/x 20130001");
		제출(회원("log3"));

		String log = output.getAll();
		assertThat(log).contains("IllegalStateException");
		assertThat(log).doesNotContain("판독기비밀메시지", "2013-0001", 번호정규화, permitNumberHash.of(번호정규화), "permits/",
				"Country of citizenship", "없는대학교");
		assertThat(log).doesNotContain("at com.example.demo.permit.service.PermitAutoReview"); // 스택 없음
	}
}
