package com.example.demo.visa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S11-d 완료 조건 검사: 비자 후기 통계 GET /visa-reviews/stats.
// 컨텍스트 캐시를 같이 쓰도록 다른 후기 테스트와 같은 설정(@MockitoBean Clock)을 쓴다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class VisaReviewStatsApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired ObjectMapper om;
	@Autowired EntityManagerFactory emf;
	@MockitoBean Clock clock;

	@BeforeEach
	void 준비() {
		정리();
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-09T03:00:00Z"));
		Mockito.when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from visa_reviews");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	// 접수일 2026-09-01 기준으로 처리 일수(days)만큼 뒤에 결과가 나온 후기를 DB에 바로 넣는다(작성자는 탈퇴 상태 null)
	private void 저장(String school, String visaType, String appType, int days, String result) {
		jdbc.update("insert into visa_reviews (author_id, school_id, visa_type, application_type, received_date, "
				+ "result_date, result, content, created_at) values (null, (select id from schools where name = ?), ?, ?, "
				+ "date '2026-09-01', date '2026-09-01' + ?, ?, '시드', now())", school, visaType, appType, days, result);
	}

	private void 저장(String school, String visaType, String appType, int days) {
		저장(school, visaType, appType, days, "APPROVED");
	}

	private long 학교id(String name) {
		return jdbc.queryForObject("select id from schools where name = ?", Long.class, name);
	}

	private MockHttpServletResponse 통계(String query, Cookie c) throws Exception {
		MockHttpServletRequestBuilder b = get("/visa-reviews/stats" + query);
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private MockHttpServletResponse 통계(String query) throws Exception {
		return 통계(query, null);
	}

	private JsonNode json(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	// 표준 데이터: 경희 D_2 EXTENSION 19·20일, 경희 D_2 NEW 10일, 고려 D_4 NEW 5일
	private void 표준데이터() {
		저장("경희대학교", "D_2", "EXTENSION", 19);
		저장("경희대학교", "D_2", "EXTENSION", 20);
		저장("경희대학교", "D_2", "NEW", 10);
		저장("고려대학교", "D_4", "NEW", 5);
	}

	// "경희대학교|D_2|EXTENSION" 처럼 행을 한 줄 문자열로 줄여 순서 비교에 쓴다
	private String 요약(JsonNode n) {
		return n.get("school").get("name").asString() + "|" + n.get("visaType").get("code").asString() + "|"
				+ n.get("applicationType").get("code").asString();
	}

	private JsonNode 행찾기(JsonNode arr, String 요약) {
		for (JsonNode n : arr) {
			if (요약(n).equals(요약)) {
				return n;
			}
		}
		return null;
	}

	// ============ 조건 1: 비로그인 조회와 값 ============

	@Test
	void 조건1_비로그인으로_조회하면_200이고_조합_3개의_건수_평균_이름이_맞다() throws Exception {
		표준데이터();
		MockHttpServletResponse res = 통계("");
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = json(res);
		assertThat(arr.size()).isEqualTo(3);
		JsonNode row = 행찾기(arr, "경희대학교|D_2|EXTENSION");
		assertThat(row).isNotNull();
		assertThat(row.get("count").asLong()).isEqualTo(2);
		assertThat(row.get("avgDays").asDouble()).isEqualTo(19.5);
		assertThat(row.get("school").get("id").asLong()).isEqualTo(학교id("경희대학교"));
		assertThat(row.get("visaType").get("name").asString()).isEqualTo("D-2 유학");
	}

	@Test
	void 조건1_만료_변조된_쿠키가_붙어도_200이다() throws Exception {
		표준데이터();
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("username", "x")
				.issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(2700)).build();
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		assertThat(통계("", new Cookie("access_token", expired)).getStatus()).isEqualTo(200);
		assertThat(통계("", new Cookie("access_token", expired + "x")).getStatus()).isEqualTo(200);
		assertThat(통계("", new Cookie("access_token", "garbage")).getStatus()).isEqualTo(200);
	}

	// ============ 조건 2: 반올림 ============

	@Test
	void 조건2_처리일_10_11_11의_평균은_10점7로_반올림된다() throws Exception {
		저장("경희대학교", "D_2", "NEW", 10);
		저장("경희대학교", "D_2", "NEW", 11);
		저장("경희대학교", "D_2", "NEW", 11);
		JsonNode row = json(통계("")).get(0);
		assertThat(row.get("count").asLong()).isEqualTo(3);
		assertThat(row.get("avgDays").asDouble()).isEqualTo(10.7);
	}

	@Test
	void 조건2_접수일과_결과일이_같으면_평균은_0점0이다() throws Exception {
		저장("경희대학교", "D_2", "NEW", 0);
		assertThat(json(통계("")).get(0).get("avgDays").asDouble()).isEqualTo(0.0);
	}

	@Test
	void 조건2_1일과_2일의_평균_1점5는_그대로다() throws Exception {
		저장("경희대학교", "D_2", "NEW", 1);
		저장("경희대학교", "D_2", "NEW", 2);
		assertThat(json(통계("")).get(0).get("avgDays").asDouble()).isEqualTo(1.5);
	}

	@Test
	void 조건2_평균_12점25는_절반_올림으로_12점3이다() throws Exception {
		저장("경희대학교", "D_2", "NEW", 12);
		저장("경희대학교", "D_2", "NEW", 12);
		저장("경희대학교", "D_2", "NEW", 12);
		저장("경희대학교", "D_2", "NEW", 13);
		assertThat(json(통계("")).get(0).get("avgDays").asDouble()).isEqualTo(12.3);
	}

	// ============ 조건 3: 0건 조합 없음, 불허 포함 ============

	@Test
	void 조건3_후기가_없으면_200과_빈_배열이다() throws Exception {
		MockHttpServletResponse res = 통계("");
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).isArray()).isTrue();
		assertThat(json(res).size()).isZero();
	}

	@Test
	void 조건3_배열_길이는_실제_존재하는_조합_수와_같다() throws Exception {
		표준데이터();
		저장("고려대학교", "D_4", "NEW", 7); // 같은 조합이라 행이 늘지 않는다
		assertThat(json(통계("")).size()).isEqualTo(3);
		assertThat(json(통계("")).size())
				.isEqualTo(jdbc.queryForObject("select count(*) from (select distinct school_id, visa_type, "
						+ "application_type from visa_reviews) t", Long.class).intValue());
	}

	@Test
	void 조건3_불허_건도_건수와_평균에_포함된다() throws Exception {
		저장("경희대학교", "D_2", "NEW", 10, "APPROVED");
		저장("경희대학교", "D_2", "NEW", 20, "DENIED");
		JsonNode row = json(통계("")).get(0);
		assertThat(row.get("count").asLong()).isEqualTo(2);
		assertThat(row.get("avgDays").asDouble()).isEqualTo(15.0);
	}

	// ============ 조건 4: 정렬 ============

	@Test
	void 조건4_학교_가나다_비자_선언순_신청_선언순으로_정렬된다() throws Exception {
		// 일부러 뒤섞인 순서로 넣는다
		저장("고려대학교", "D_4", "NEW", 5);
		저장("경희대학교", "D_2", "EXTENSION", 19);
		저장("경희대학교", "D_2", "NEW", 10);
		JsonNode arr = json(통계(""));
		assertThat(arr.size()).isEqualTo(3);
		assertThat(요약(arr.get(0))).isEqualTo("경희대학교|D_2|NEW");
		assertThat(요약(arr.get(1))).isEqualTo("경희대학교|D_2|EXTENSION");
		assertThat(요약(arr.get(2))).isEqualTo("고려대학교|D_4|NEW");
	}

	@Test
	void 조건4_같은_학교에서_비자_종류는_선언순_D_2_D_4_E_7_OTHER_순이다() throws Exception {
		저장("경희대학교", "OTHER", "NEW", 1);
		저장("경희대학교", "E_7", "NEW", 1);
		저장("경희대학교", "D_4", "NEW", 1);
		저장("경희대학교", "D_2", "NEW", 1);
		JsonNode arr = json(통계(""));
		assertThat(요약(arr.get(0))).isEqualTo("경희대학교|D_2|NEW");
		assertThat(요약(arr.get(1))).isEqualTo("경희대학교|D_4|NEW");
		assertThat(요약(arr.get(2))).isEqualTo("경희대학교|E_7|NEW");
		assertThat(요약(arr.get(3))).isEqualTo("경희대학교|OTHER|NEW");
	}

	// ============ 조건 5: 거르기 ============

	@Test
	void 조건5_schoolId만_주면_그_학교_행만_나온다() throws Exception {
		표준데이터();
		JsonNode arr = json(통계("?schoolId=" + 학교id("경희대학교")));
		assertThat(arr.size()).isEqualTo(2);
		for (JsonNode n : arr) {
			assertThat(n.get("school").get("name").asString()).isEqualTo("경희대학교");
		}
	}

	@Test
	void 조건5_visaType과_applicationType을_함께_주면_그_조합만_나온다() throws Exception {
		표준데이터();
		JsonNode arr = json(통계("?visaType=D_2&applicationType=EXTENSION"));
		assertThat(arr.size()).isEqualTo(1);
		assertThat(요약(arr.get(0))).isEqualTo("경희대학교|D_2|EXTENSION");
		assertThat(arr.get(0).get("count").asLong()).isEqualTo(2);
	}

	@Test
	void 조건5_빈_값_visaType은_파라미터_없음과_같은_결과다() throws Exception {
		표준데이터();
		MockHttpServletResponse res = 통계("?visaType=");
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(res.getContentAsString()).isEqualTo(통계("").getContentAsString());
		assertThat(json(res).size()).isEqualTo(3);
	}

	@Test
	void 조건5_있는_학교인데_후기가_없으면_200과_빈_배열이다() throws Exception {
		표준데이터();
		MockHttpServletResponse res = 통계("?schoolId=" + 학교id("연세대학교"));
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).size()).isZero();
	}

	// applicationType만 줘도 그 신청 종류 행만 나온다
	@Test
	void 조건5_applicationType만_주면_그_신청_종류_행만_나온다() throws Exception {
		표준데이터();
		JsonNode arr = json(통계("?applicationType=NEW"));
		assertThat(arr.size()).isEqualTo(2);
		for (JsonNode n : arr) {
			assertThat(n.get("applicationType").get("code").asString()).isEqualTo("NEW");
		}
	}

	// schoolId + visaType을 같이 주면 AND로 걸린다
	@Test
	void 조건5_schoolId와_visaType을_함께_주면_그_조합만_나온다() throws Exception {
		표준데이터();
		저장("경희대학교", "D_4", "NEW", 8);
		JsonNode arr = json(통계("?schoolId=" + 학교id("경희대학교") + "&visaType=D_4"));
		assertThat(arr.size()).isEqualTo(1);
		assertThat(요약(arr.get(0))).isEqualTo("경희대학교|D_4|NEW");
	}

	// D_10이 들어간 선언 순서(D_2, D_4, D_10, E_7, OTHER) 확인: 문자열 정렬이면 D_10이 D_2 앞에 온다
	@Test
	void 조건4_비자_종류는_D_10도_문자열이_아닌_enum_선언순으로_정렬된다() throws Exception {
		저장("경희대학교", "OTHER", "NEW", 1);
		저장("경희대학교", "D_10", "NEW", 1);
		저장("경희대학교", "E_7", "NEW", 1);
		저장("경희대학교", "D_4", "NEW", 1);
		저장("경희대학교", "D_2", "NEW", 1);
		JsonNode arr = json(통계(""));
		assertThat(arr.size()).isEqualTo(5);
		assertThat(요약(arr.get(0))).isEqualTo("경희대학교|D_2|NEW");
		assertThat(요약(arr.get(1))).isEqualTo("경희대학교|D_4|NEW");
		assertThat(요약(arr.get(2))).isEqualTo("경희대학교|D_10|NEW");
		assertThat(요약(arr.get(3))).isEqualTo("경희대학교|E_7|NEW");
		assertThat(요약(arr.get(4))).isEqualTo("경희대학교|OTHER|NEW");
	}

	// 없는 학교(404)와 잘못된 코드(400)가 겹치면 바인딩이 서비스보다 먼저라 400
	@Test
	void 조건6_없는_schoolId와_모르는_visaType이_겹치면_400이다() throws Exception {
		assertThat(통계("?schoolId=999&visaType=F_2").getStatus()).isEqualTo(400);
	}

	// ============ 조건 6: 실패 ============

	@Test
	void 조건6_없는_schoolId는_404이다() throws Exception {
		assertThat(통계("?schoolId=999").getStatus()).isEqualTo(404);
	}

	@Test
	void 조건6_숫자가_아닌_schoolId는_400이다() throws Exception {
		assertThat(통계("?schoolId=abc").getStatus()).isEqualTo(400);
	}

	@Test
	void 조건6_모르는_visaType_코드는_400이다() throws Exception {
		assertThat(통계("?visaType=F_2").getStatus()).isEqualTo(400);
	}

	@Test
	void 조건6_모르는_applicationType_코드는_400이다() throws Exception {
		assertThat(통계("?applicationType=ZZZ").getStatus()).isEqualTo(400);
	}

	// ============ 조건 7: 쿼리 수 ============

	// 네이티브 SQL도 잡히도록 준비된 SQL 문장 수(getPrepareStatementCount)로 센다
	private long 쿼리수(String query) throws Exception {
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			assertThat(통계(query).getStatus()).isEqualTo(200);
			return stats.getPrepareStatementCount();
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	@Test
	void 조건7_후기가_1건이든_20건이든_쿼리는_조건없음_1번_schoolId있음_2번이다() throws Exception {
		저장("경희대학교", "D_2", "NEW", 3);
		String withSchool = "?schoolId=" + 학교id("경희대학교");
		long 하나_없음 = 쿼리수("");
		long 하나_학교 = 쿼리수(withSchool);
		for (int i = 0; i < 19; i++) {
			저장(i % 2 == 0 ? "경희대학교" : "고려대학교", i % 3 == 0 ? "D_2" : "D_4", i % 4 == 0 ? "NEW" : "EXTENSION", i);
		}
		long 스무개_없음 = 쿼리수("");
		long 스무개_학교 = 쿼리수(withSchool);
		assertThat(하나_없음).as("조건 없음 1건").isEqualTo(1);
		assertThat(스무개_없음).as("조건 없음 20건").isEqualTo(1);
		assertThat(하나_학교).as("schoolId 1건").isEqualTo(2);
		assertThat(스무개_학교).as("schoolId 20건").isEqualTo(2);
	}

	// ============ 조건 8: 경로 충돌 없음 ============

	@Test
	void 조건8_stats는_id_경로로_가지_않고_기존_단건_조회는_그대로다() throws Exception {
		저장("경희대학교", "D_2", "NEW", 3);
		assertThat(통계("").getStatus()).isEqualTo(200);
		long id = jdbc.queryForObject("select id from visa_reviews limit 1", Long.class);
		assertThat(mockMvc.perform(get("/visa-reviews/" + id)).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(get("/visa-reviews/abc")).andReturn().getResponse().getStatus()).isEqualTo(400);
	}
}
