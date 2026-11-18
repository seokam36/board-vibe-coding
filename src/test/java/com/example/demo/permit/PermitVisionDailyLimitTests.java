package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

import tools.jackson.databind.JsonNode;

// S21-d: 구글 Vision 하루 전체 상한(모든 회원 합계, 서울 날짜). 상한 2로 vision 모드를 띄우고 판독기는 가짜를 쓴다(구글은 안 부름).
// 시계(Clock)는 가짜로 바꿔 "오늘/다음 날"을 만든다. 번호·이름·키는 모두 지어낸 값이다.
@SpringBootTest(properties = { "app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=TESTKEY-never-real-5566",
		"app.permit.ocr.vision.base-url=http://127.0.0.1:1", "app.permit.ocr.vision.global-per-day=2" })
@Import({ TestcontainersConfiguration.class, FakePermitTextReader.Config.class })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PermitVisionDailyLimitTests extends AdminPermitTestBase {

	@Autowired FakePermitTextReader 판독기;
	@MockitoBean Clock clock;

	static final String 오늘키 = "permit-vision-day:2026-11-18";
	static final String 내일키 = "permit-vision-day:2026-11-19";

	@BeforeEach
	void 준비() {
		판독기.reset();
		날짜정하기("2026-11-18T03:00:00Z"); // 서울 2026-11-18 12:00 (카운터 키 정리는 AdminPermitTestBase가 한다)
	}

	private void 날짜정하기(String utcInstant) {
		Mockito.when(clock.instant()).thenReturn(Instant.parse(utcInstant));
		Mockito.when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
	}

	private static String 완벽(String 번호) {
		return String.join("\n", "표준입학허가서", "국적(Country of citizenship): 베트남", "학교명(School name): 연세대학교",
				"표준입학허가서 번호(Certificate of Admission No.): " + 번호);
	}

	private MockHttpServletResponse 제출(User u) throws Exception {
		return mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
	}

	private String 기계결과(User u) {
		return jdbc.queryForObject("select machine_result from permit_requests where user_id = ?", String.class, u.getId());
	}

	// 완료 조건 1: 세 번째 신청은 구글(판독기)을 안 부르고 PENDING + UNREADABLE
	@Test
	void 상한_2일때_같은_날_세_번째_신청은_판독기를_부르지_않고_201_PENDING_UNREADABLE이다() throws Exception {
		판독기.text = null; // 못 읽는 글자라도 호출 수만 센다
		User a = 회원("vlim1a");
		User b = 회원("vlim1b");
		User c = 회원("vlim1c");

		assertThat(제출(a).getStatus()).isEqualTo(201);
		assertThat(제출(b).getStatus()).isEqualTo(201);
		MockHttpServletResponse third = 제출(c);

		assertThat(판독기.calls).isEqualTo(2);
		assertThat(third.getStatus()).isEqualTo(201);
		JsonNode body = json(third);
		assertThat(body.get("status").asText()).isEqualTo("PENDING");
		assertThat(기계결과(c)).isEqualTo("UNREADABLE");
	}

	// 완료 조건 2: 상한 안의 신청은 정상 판독·판정(자동 승인)
	@Test
	void 상한_안의_신청은_판독되고_네_관문_통과_글자면_APPROVED이다() throws Exception {
		판독기.text = 완벽("제 2013-0101 호");
		User a = 회원("vlim2a");

		MockHttpServletResponse r = 제출(a);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("APPROVED");
		assertThat(판독기.calls).isEqualTo(1);
		assertThat(기계결과(a)).isEqualTo("ALL_PASSED");
	}

	// 완료 조건 2 보강: 상한을 넘은 신청은 글자가 완벽해도 자동 승인되지 않는다
	@Test
	void 상한을_넘은_신청은_완벽한_글자여도_APPROVED가_되지_않고_대기한다() throws Exception {
		판독기.text = 완벽("제 2013-0201 호");
		User a = 회원("vlim2b1");
		User b = 회원("vlim2b2");
		User c = 회원("vlim2b3");
		제출(a);
		판독기.text = 완벽("제 2013-0202 호");
		제출(b);
		판독기.text = 완벽("제 2013-0203 호");

		MockHttpServletResponse third = 제출(c);

		assertThat(json(third).get("status").asText()).isEqualTo("PENDING");
		assertThat(인증학교(c)).isNull();
	}

	// 완료 조건 3: 날짜가 바뀌면 카운터가 새로 시작
	@Test
	void 날짜가_바뀌면_다시_판독기를_부른다() throws Exception {
		판독기.text = null;
		제출(회원("vlim3a"));
		제출(회원("vlim3b"));
		제출(회원("vlim3c"));
		assertThat(판독기.calls).isEqualTo(2);

		날짜정하기("2026-11-19T03:00:00Z"); // 다음 날
		제출(회원("vlim3d"));

		assertThat(판독기.calls).isEqualTo(3);
		assertThat(redis.opsForValue().get(내일키)).isEqualTo("1");
	}

	// 완료 조건 9: 키에 TTL이 걸려 있다(0 초과, 2일 이하)
	@Test
	void 카운터_키에_TTL이_걸려_있고_2일_이하이다() throws Exception {
		제출(회원("vlim9"));

		assertThat(redis.opsForValue().get(오늘키)).isEqualTo("1");
		Long ttl = redis.getExpire(오늘키);
		assertThat(ttl).isNotNull().isGreaterThan(0L).isLessThanOrEqualTo(2L * 24 * 60 * 60);
	}

	// 완료 조건 8: 상한 초과 WARN은 처음 넘을 때 1번만, 개인정보 없음
	@Test
	void 상한_초과_경고_로그는_한_번만_남고_회원_사진_번호가_없다(CapturedOutput output) throws Exception {
		판독기.text = null;
		User[] us = new User[5];
		for (int i = 0; i < 5; i++) {
			us[i] = 회원("vlim8" + i);
			제출(us[i]);
		}

		String log = output.getAll();
		long warnCount = log.lines().filter(l -> l.contains("하루 전체 상한") && l.contains("도달했습니다")).count();
		assertThat(warnCount).isEqualTo(1);
		String warnLine = log.lines().filter(l -> l.contains("도달했습니다")).findFirst().orElseThrow();
		assertThat(warnLine).doesNotContain("userId").doesNotContain("requestId").doesNotContain("photo")
				.doesNotContain("TESTKEY").doesNotContain("2013");
		for (User u : us) {
			assertThat(warnLine).doesNotContain("vlim8").doesNotContain("id=" + u.getId());
		}
	}
}
