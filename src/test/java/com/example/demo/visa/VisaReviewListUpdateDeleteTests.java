package com.example.demo.visa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

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
import org.springframework.http.MediaType;
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
import org.springframework.transaction.PlatformTransactionManager;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.domain.UserRole;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// S11-c 완료 조건 검사: 비자 후기 목록 거르기(GET /visa-reviews), 수정(PUT), 삭제(DELETE).
// 컨텍스트 캐시를 같이 쓰도록 VisaReviewApiTests와 같은 설정(@MockitoBean Clock)을 쓴다. "오늘"은 서울 2026-11-09.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class VisaReviewListUpdateDeleteTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired ObjectMapper om;
	@Autowired EntityManagerFactory emf;
	@Autowired PlatformTransactionManager txManager;
	@MockitoBean Clock clock;

	User 회원; // 연세대 인증 회원(후기 주인)
	User 다른회원; // 고려대 인증 회원
	User 관리자;
	Cookie 쿠키;
	Cookie 다른쿠키;
	Cookie 관리자쿠키;

	@BeforeEach
	void 준비() {
		정리();
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-09T03:00:00Z"));
		Mockito.when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "vuser", "연세대학교");
		다른회원 = TestLogin.인증된_사용자(userRepository, jdbc, "other", "고려대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "vadmin");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		다른쿠키 = TestLogin.쿠키(tokenService, 다른회원);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from visa_reviews"); // 후기가 회원보다 먼저(FK 순서)
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	// 후기를 DB에 바로 넣고 id를 돌려준다
	private long 저장(User author, String school, String visaType, String appType) {
		return jdbc.queryForObject("insert into visa_reviews (author_id, school_id, visa_type, application_type, received_date, "
				+ "result_date, result, content, created_at) values (?, (select id from schools where name = ?), ?, ?, "
				+ "'2026-09-01', '2026-10-03', 'APPROVED', '시드', now() - interval '1 day') returning id", Long.class,
				author == null ? null : author.getId(), school, visaType, appType);
	}

	private long 저장(User author, String school) {
		return 저장(author, school, "D_2", "EXTENSION");
	}

	private long 학교id(String name) {
		return jdbc.queryForObject("select id from schools where name = ?", Long.class, name);
	}

	private JsonNode json(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	private MockHttpServletResponse 목록(String query, Cookie c) throws Exception {
		MockHttpServletRequestBuilder b = get("/visa-reviews" + query);
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private List<Long> ids(MockHttpServletResponse r) throws Exception {
		List<Long> out = new ArrayList<>();
		for (JsonNode n : json(r)) {
			out.add(n.get("id").asLong());
		}
		return out;
	}

	private static final String 수정본문 = "{\"visaType\":\"E_7\",\"applicationType\":\"CHANGE\",\"receivedDate\":\"2026-10-01\","
			+ "\"resultDate\":\"2026-10-11\",\"result\":\"DENIED\",\"content\":\"수정됨\"}";

	private MockHttpServletResponse 수정(Object id, String body, Cookie c, boolean withCsrf) throws Exception {
		MockHttpServletRequestBuilder b = put("/visa-reviews/" + id).contentType(MediaType.APPLICATION_JSON).content(body);
		if (c != null) {
			b.cookie(c);
		}
		if (withCsrf) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private int 수정상태(Object id, String body, Cookie c) throws Exception {
		return 수정(id, body, c, true).getStatus();
	}

	private int 삭제(Object id, Cookie c, boolean withCsrf) throws Exception {
		MockHttpServletRequestBuilder b = delete("/visa-reviews/" + id);
		if (c != null) {
			b.cookie(c);
		}
		if (withCsrf) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse().getStatus();
	}

	private int 단건상태(long id) throws Exception {
		return mockMvc.perform(get("/visa-reviews/" + id)).andReturn().getResponse().getStatus();
	}

	private long 후기수() {
		return jdbc.queryForObject("select count(*) from visa_reviews", Long.class);
	}

	// ============ 조건 1: 목록 기본 ============

	@Test
	void 조건1_조건이_없으면_전체를_최신순_id_내림차순으로_돌려준다_비로그인_200() throws Exception {
		long a = 저장(회원, "연세대학교");
		long b = 저장(다른회원, "고려대학교");
		long c = 저장(회원, "연세대학교");
		MockHttpServletResponse res = 목록("", null);
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(ids(res)).containsExactly(c, b, a);
		JsonNode first = json(res).get(0);
		assertThat(first.get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(first.get("author").get("nickname").asString()).isEqualTo("vuser");
	}

	@Test
	void 조건1_만료_변조_쿠키가_붙어도_목록은_200이다() throws Exception {
		저장(회원, "연세대학교");
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(String.valueOf(회원.getId())).claim("username", "x")
				.issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(2700)).build();
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		assertThat(목록("", new Cookie("access_token", expired)).getStatus()).isEqualTo(200);
		assertThat(목록("", new Cookie("access_token", 쿠키.getValue() + "x")).getStatus()).isEqualTo(200);
	}

	// ============ 조건 2: 거르기 ============

	// 학교·비자종류·신청종류가 서로 다른 후기 5개를 만든다
	// r1 연세 D_2 EXTENSION / r2 연세 D_4 NEW / r3 고려 D_2 NEW / r4 고려 D_2 EXTENSION / r5 연세 D_2 NEW
	@Test
	void 조건2_schoolId만_주면_그_학교_후기만_나온다() throws Exception {
		long r1 = 저장(회원, "연세대학교", "D_2", "EXTENSION");
		long r2 = 저장(회원, "연세대학교", "D_4", "NEW");
		저장(다른회원, "고려대학교", "D_2", "NEW");
		저장(다른회원, "고려대학교", "D_2", "EXTENSION");
		long r5 = 저장(회원, "연세대학교", "D_2", "NEW");
		assertThat(ids(목록("?schoolId=" + 학교id("연세대학교"), null))).containsExactly(r5, r2, r1);
	}

	@Test
	void 조건2_visaType만_주면_그_종류만_나온다() throws Exception {
		long r1 = 저장(회원, "연세대학교", "D_2", "EXTENSION");
		저장(회원, "연세대학교", "D_4", "NEW");
		long r3 = 저장(다른회원, "고려대학교", "D_2", "NEW");
		저장(다른회원, "고려대학교", "E_7", "EXTENSION");
		assertThat(ids(목록("?visaType=D_2", null))).containsExactly(r3, r1);
	}

	@Test
	void 조건2_applicationType만_주면_그_신청종류만_나온다() throws Exception {
		long r1 = 저장(회원, "연세대학교", "D_2", "EXTENSION");
		저장(회원, "연세대학교", "D_4", "NEW");
		저장(다른회원, "고려대학교", "D_2", "NEW");
		long r4 = 저장(다른회원, "고려대학교", "D_2", "EXTENSION");
		assertThat(ids(목록("?applicationType=EXTENSION", null))).containsExactly(r4, r1);
	}

	@Test
	void 조건2_세_조건을_모두_주면_AND로_정확히_걸러진다() throws Exception {
		저장(회원, "연세대학교", "D_2", "EXTENSION");
		저장(회원, "연세대학교", "D_4", "NEW");
		저장(다른회원, "고려대학교", "D_2", "NEW");
		long r4 = 저장(다른회원, "고려대학교", "D_2", "EXTENSION");
		저장(다른회원, "고려대학교", "D_4", "EXTENSION");
		assertThat(ids(목록("?schoolId=" + 학교id("고려대학교") + "&visaType=D_2&applicationType=EXTENSION", null)))
				.containsExactly(r4);
	}

	@Test
	void 조건2_맞는_후기가_없으면_빈_배열_200이다() throws Exception {
		저장(회원, "연세대학교", "D_2", "EXTENSION");
		MockHttpServletResponse res = 목록("?visaType=E_7", null);
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).size()).isZero();
	}

	// ============ 조건 3: 잘못된 조건 ============

	@Test
	void 조건3_없는_schoolId는_404이다() throws Exception {
		assertThat(목록("?schoolId=999999999", null).getStatus()).isEqualTo(404);
	}

	@Test
	void 조건3_숫자가_아닌_schoolId는_400이다() throws Exception {
		assertThat(목록("?schoolId=abc", null).getStatus()).isEqualTo(400);
	}

	@Test
	void 조건3_모르는_visaType_코드는_400이다() throws Exception {
		assertThat(목록("?visaType=F_2", null).getStatus()).isEqualTo(400);
	}

	@Test
	void 조건3_모르는_applicationType_코드는_400이다() throws Exception {
		assertThat(목록("?applicationType=ZZZ", null).getStatus()).isEqualTo(400);
	}

	@Test
	void 조건3_빈_값_visaType은_조건_없음이라_전체가_나온다() throws Exception {
		long a = 저장(회원, "연세대학교", "D_2", "EXTENSION");
		long b = 저장(다른회원, "고려대학교", "E_7", "NEW");
		MockHttpServletResponse res = 목록("?visaType=", null);
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(ids(res)).containsExactly(b, a);
	}

	// ============ 조건 4: 쿼리 수, 탈퇴 작성자 ============

	private long 쿼리수(String query) throws Exception {
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			MockHttpServletResponse res = 목록(query, null);
			assertThat(res.getStatus()).isEqualTo(200);
			return stats.getPrepareStatementCount();
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	@Test
	void 조건4_후기가_1개일_때와_5개일_때_쿼리_수가_같다_조건없음_1번_schoolId있음_2번() throws Exception {
		저장(회원, "연세대학교");
		String withSchool = "?schoolId=" + 학교id("연세대학교");
		long 하나_없음 = 쿼리수("");
		long 하나_학교 = 쿼리수(withSchool);
		for (int i = 0; i < 4; i++) {
			저장(i % 2 == 0 ? 회원 : 다른회원, i % 2 == 0 ? "연세대학교" : "고려대학교");
		}
		long 다섯_없음 = 쿼리수("");
		long 다섯_학교 = 쿼리수(withSchool);
		assertThat(하나_없음).as("조건 없음 1개").isEqualTo(1);
		assertThat(다섯_없음).as("조건 없음 5개").isEqualTo(1);
		assertThat(하나_학교).as("schoolId 1개").isEqualTo(2);
		assertThat(다섯_학교).as("schoolId 5개").isEqualTo(2);
	}

	@Test
	void 조건4_작성자가_탈퇴한_후기는_author가_null로_나온다() throws Exception {
		저장(null, "연세대학교");
		MockHttpServletResponse res = 목록("", null);
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).get(0).get("author").isNull()).isTrue();
	}

	// ============ 조건 5: 수정 성공 ============

	@Test
	void 조건5_본인이_수정하면_200이고_응답과_DB의_모든_칸이_바뀌며_학교_작성자_작성시각은_그대로다() throws Exception {
		long id = 저장(회원, "연세대학교");
		Object beforeCreated = jdbc.queryForObject("select created_at from visa_reviews where id = ?", Object.class, id);
		MockHttpServletResponse res = 수정(id, 수정본문, 쿠키, true);
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("id").asLong()).isEqualTo(id);
		assertThat(b.get("visaType").get("code").asString()).isEqualTo("E_7");
		assertThat(b.get("applicationType").get("code").asString()).isEqualTo("CHANGE");
		assertThat(b.get("result").get("code").asString()).isEqualTo("DENIED");
		assertThat(b.get("receivedDate").asString()).isEqualTo("2026-10-01");
		assertThat(b.get("resultDate").asString()).isEqualTo("2026-10-11");
		assertThat(b.get("processingDays").asLong()).isEqualTo(10);
		assertThat(b.get("content").asString()).isEqualTo("수정됨");
		assertThat(b.get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(b.get("author").get("id").asLong()).isEqualTo(회원.getId());

		var row = jdbc.queryForMap("select * from visa_reviews where id = ?", id);
		assertThat(row.get("visa_type")).isEqualTo("E_7");
		assertThat(row.get("application_type")).isEqualTo("CHANGE");
		assertThat(row.get("result")).isEqualTo("DENIED");
		assertThat(row.get("received_date").toString()).isEqualTo("2026-10-01");
		assertThat(row.get("result_date").toString()).isEqualTo("2026-10-11");
		assertThat(row.get("content")).isEqualTo("수정됨");
		assertThat(row.get("school_id")).isEqualTo(학교id("연세대학교"));
		assertThat(row.get("author_id")).isEqualTo(회원.getId());
		assertThat(row.get("created_at")).isEqualTo(beforeCreated);
	}

	@Test
	void 조건5_수정할_때_content를_생략하면_빈_문자열이_된다() throws Exception {
		long id = 저장(회원, "연세대학교");
		ObjectNode n = (ObjectNode) om.readTree(수정본문);
		n.remove("content");
		MockHttpServletResponse res = 수정(id, n.toString(), 쿠키, true);
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).get("content").asString()).isEmpty();
		assertThat(jdbc.queryForObject("select content from visa_reviews where id = ?", String.class, id)).isEmpty();
	}

	// ============ 조건 6: 수정 권한 ============

	@Test
	void 조건6_남의_후기_수정은_403이고_DB는_그대로다() throws Exception {
		long id = 저장(다른회원, "고려대학교");
		assertThat(수정상태(id, 수정본문, 쿠키)).isEqualTo(403);
		assertThat(jdbc.queryForObject("select content from visa_reviews where id = ?", String.class, id)).isEqualTo("시드");
	}

	@Test
	void 조건6_관리자도_남의_후기_수정은_403이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(수정상태(id, 수정본문, 관리자쿠키)).isEqualTo(403);
		assertThat(jdbc.queryForObject("select content from visa_reviews where id = ?", String.class, id)).isEqualTo("시드");
	}

	@Test
	void 조건6_없는_id_수정은_404이다() throws Exception {
		assertThat(수정상태(999999999L, 수정본문, 쿠키)).isEqualTo(404);
	}

	@Test
	void 조건6_비로그인_수정은_401이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(수정(id, 수정본문, null, true).getStatus()).isEqualTo(401);
	}

	@Test
	void 조건6_CSRF_없는_수정은_403이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(수정(id, 수정본문, 쿠키, false).getStatus()).isEqualTo(403);
		assertThat(jdbc.queryForObject("select content from visa_reviews where id = ?", String.class, id)).isEqualTo("시드");
	}

	@Test
	void 조건6_탈퇴한_회원이_쓴_후기_author_null_는_누구도_수정할_수_없어_403이다() throws Exception {
		long id = 저장(null, "연세대학교");
		assertThat(수정상태(id, 수정본문, 쿠키)).isEqualTo(403);
	}

	// ============ 조건 7: 수정 400 ============

	@Test
	void 조건7_수정_본문의_필수값이_하나씩_빠지면_400이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		for (String f : new String[] { "visaType", "applicationType", "result", "receivedDate", "resultDate" }) {
			ObjectNode n = (ObjectNode) om.readTree(수정본문);
			n.remove(f);
			assertThat(수정상태(id, n.toString(), 쿠키)).as("%s 누락", f).isEqualTo(400);
		}
		assertThat(jdbc.queryForObject("select content from visa_reviews where id = ?", String.class, id)).isEqualTo("시드");
	}

	@Test
	void 조건7_수정_시_결과일이_접수일보다_빠르면_400이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(수정상태(id, 수정본문.replace("2026-10-11", "2026-09-30"), 쿠키)).isEqualTo(400);
	}

	@Test
	void 조건7_수정_시_서울_오늘_플러스_1일이면_400이고_오늘이면_200이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		String 내일 = 수정본문.replace("2026-10-01", "2026-11-09").replace("2026-10-11", "2026-11-10");
		assertThat(수정상태(id, 내일, 쿠키)).isEqualTo(400);
		String 오늘 = 수정본문.replace("2026-10-01", "2026-11-09").replace("2026-10-11", "2026-11-09");
		assertThat(수정상태(id, 오늘, 쿠키)).isEqualTo(200);
	}

	@Test
	void 조건7_수정_시_content_2001자는_400이고_2000자는_200이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(수정상태(id, 수정본문.replace("수정됨", "a".repeat(2001)), 쿠키)).isEqualTo(400);
		assertThat(수정상태(id, 수정본문.replace("수정됨", "a".repeat(2000)), 쿠키)).isEqualTo(200);
	}

	@Test
	void 조건7_남의_후기라도_본문이_잘못되면_403이_아니라_400이다_검사_순서() throws Exception {
		long id = 저장(다른회원, "고려대학교");
		// 필수 누락(@Valid)과 날짜 규칙 둘 다 확인
		ObjectNode n = (ObjectNode) om.readTree(수정본문);
		n.remove("visaType");
		assertThat(수정상태(id, n.toString(), 쿠키)).as("필수 누락").isEqualTo(400);
		assertThat(수정상태(id, 수정본문.replace("2026-10-11", "2026-09-30"), 쿠키)).as("날짜 순서").isEqualTo(400);
	}

	// ============ 조건 8: 수정은 학교 인증과 무관 ============

	@Test
	void 조건8_학교_인증이_풀린_본인도_자기_후기_수정은_200이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		jdbc.update("update users set verified_school_id = null, verified_at = null, school_email_hash = null where id = ?",
				회원.getId());
		assertThat(수정상태(id, 수정본문, 쿠키)).isEqualTo(200);
		assertThat(jdbc.queryForObject("select content from visa_reviews where id = ?", String.class, id)).isEqualTo("수정됨");
	}

	// ============ 조건 9: 삭제 ============

	@Test
	void 조건9_본인이_삭제하면_204이고_이후_단건_조회는_404이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(삭제(id, 쿠키, true)).isEqualTo(204);
		assertThat(단건상태(id)).isEqualTo(404);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건9_관리자는_남의_후기를_삭제할_수_있다_204() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(삭제(id, 관리자쿠키, true)).isEqualTo(204);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건9_관리자는_작성자가_탈퇴한_후기도_삭제할_수_있다() throws Exception {
		long id = 저장(null, "연세대학교");
		assertThat(삭제(id, 관리자쿠키, true)).isEqualTo(204);
	}

	@Test
	void 조건9_일반_회원이_남의_후기를_삭제하면_403이고_후기는_남는다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(삭제(id, 다른쿠키, true)).isEqualTo(403);
		assertThat(후기수()).isEqualTo(1);
	}

	@Test
	void 조건9_없는_id_삭제는_404이다() throws Exception {
		assertThat(삭제(999999999L, 쿠키, true)).isEqualTo(404);
	}

	@Test
	void 조건9_비로그인_삭제는_401이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(삭제(id, null, true)).isEqualTo(401);
		assertThat(후기수()).isEqualTo(1);
	}

	@Test
	void 조건9_CSRF_없는_삭제는_403이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		assertThat(삭제(id, 쿠키, false)).isEqualTo(403);
		assertThat(후기수()).isEqualTo(1);
	}

	// ============ 조건 10: 묵은 관리자 토큰 ============

	@Test
	void 조건10_토큰은_관리자인데_DB에서_USER로_강등됐으면_삭제는_403이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		jdbc.update("update users set role = 'USER' where id = ?", 관리자.getId());
		assertThat(삭제(id, 관리자쿠키, true)).isEqualTo(403);
		assertThat(후기수()).isEqualTo(1);
	}

	@Test
	void 조건10_토큰은_관리자인데_회원_행이_없으면_삭제는_401이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		Cookie ghostAdmin = 관리자쿠키; // 토큰은 ADMIN 그대로 두고 회원 행만 지운다
		jdbc.update("delete from users where id = ?", 관리자.getId());
		assertThat(삭제(id, ghostAdmin, true)).isEqualTo(401);
		assertThat(후기수()).isEqualTo(1);
	}

	// ============ 조건 11: 동시 삭제 ============

	@Test
	void 조건11_관리자가_지우는_동안_다른_쪽이_먼저_지우고_커밋하면_404이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from visa_reviews where id = ?", id), // 커밋 전 삭제 = 후기 행 잠금
				() -> 삭제(id, 관리자쿠키, true), () -> {
				});
		assertThat(status).isEqualTo(404);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건11_본인이_지우는_동안_다른_쪽이_먼저_지우고_커밋하면_404이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from visa_reviews where id = ?", id),
				() -> 삭제(id, 쿠키, true), () -> {
				});
		assertThat(status).isEqualTo(404);
		assertThat(후기수()).isZero();
	}

	// ============ 조건 12: 삭제도 학교 인증과 무관 ============

	@Test
	void 조건12_학교_인증이_풀린_본인도_삭제는_204이다() throws Exception {
		long id = 저장(회원, "연세대학교");
		jdbc.update("update users set verified_school_id = null, verified_at = null, school_email_hash = null where id = ?",
				회원.getId());
		assertThat(삭제(id, 쿠키, true)).isEqualTo(204);
		assertThat(후기수()).isZero();
	}
}
