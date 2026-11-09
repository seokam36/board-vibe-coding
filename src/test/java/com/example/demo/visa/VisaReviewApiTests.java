package com.example.demo.visa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import com.example.demo.config.ClockConfig;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// S11-b 완료 조건 검사: 비자 후기 작성(POST /visa-reviews), 단건 조회(GET /visa-reviews/{id}), 탈퇴 연동.
// 시계(Clock)는 가짜로 바꿔 "오늘"을 고정한다(기본: 서울 2026-11-09 12:00).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class VisaReviewApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired ObjectMapper om;
	@Autowired EntityManagerFactory emf;
	@Autowired PlatformTransactionManager txManager;
	@MockitoBean Clock clock;

	User 회원; // 연세대 인증 회원
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		시계고정("2026-11-09T03:00:00Z"); // 서울 2026-11-09 12:00
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "vuser", "연세대학교");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
	}

	@AfterEach
	void 정리() {
		// 후기가 회원·글보다 먼저(FK 순서)
		jdbc.update("delete from visa_reviews");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	// 가짜 시계: 항상 이 시각을 서울 시간대로 알려 준다
	private void 시계고정(String utcInstant) {
		Mockito.when(clock.instant()).thenReturn(Instant.parse(utcInstant));
		Mockito.when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
	}

	private static String 본문(String received, String result) {
		return "{\"visaType\":\"D_2\",\"applicationType\":\"EXTENSION\",\"receivedDate\":\"" + received
				+ "\",\"resultDate\":\"" + result + "\",\"result\":\"APPROVED\",\"content\":\"후기\"}";
	}

	private MockHttpServletResponse 작성(String json, Cookie c, boolean withCsrf) throws Exception {
		MockHttpServletRequestBuilder b = post("/visa-reviews").contentType(MediaType.APPLICATION_JSON).content(json);
		if (c != null) {
			b.cookie(c);
		}
		if (withCsrf) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private int 작성상태(String json) throws Exception {
		return 작성(json, 쿠키, true).getStatus();
	}

	private JsonNode json(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	private long 후기수() {
		return jdbc.queryForObject("select count(*) from visa_reviews", Long.class);
	}

	private MockHttpServletResponse 조회(String idPart, Cookie c) throws Exception {
		MockHttpServletRequestBuilder b = get("/visa-reviews/" + idPart);
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	// 후기를 DB에 바로 넣는다(접수 09-01, 결과 10-03 = 32일)
	private long 후기저장(User author, String school) {
		return jdbc.queryForObject("insert into visa_reviews (author_id, school_id, visa_type, application_type, received_date, "
				+ "result_date, result, content, created_at) values (?, (select id from schools where name = ?), 'D_2', 'EXTENSION', "
				+ "'2026-09-01', '2026-10-03', 'APPROVED', '시드', now()) returning id", Long.class,
				author == null ? null : author.getId(), school);
	}

	// ============ 조건 1: 성공 응답 ============

	@Test
	void 조건1_연세대_회원이_올바른_본문으로_작성하면_201과_Location과_응답_모양이_맞다() throws Exception {
		MockHttpServletResponse res = 작성(본문("2026-09-01", "2026-10-03"), 쿠키, true);
		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode b = json(res);
		long id = b.get("id").asLong();
		assertThat(res.getHeader("Location")).isEqualTo("/visa-reviews/" + id);
		assertThat(b.get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(b.get("visaType").get("code").asString()).isEqualTo("D_2");
		assertThat(b.get("visaType").get("name").asString()).isNotBlank();
		assertThat(b.get("applicationType").get("code").asString()).isEqualTo("EXTENSION");
		assertThat(b.get("applicationType").get("name").asString()).isNotBlank();
		assertThat(b.get("result").get("code").asString()).isEqualTo("APPROVED");
		assertThat(b.get("result").get("name").asString()).isNotBlank();
		assertThat(b.get("processingDays").asLong()).isEqualTo(32);
		assertThat(b.get("author").get("id").asLong()).isEqualTo(회원.getId());
		assertThat(b.get("author").get("nickname").asString()).isEqualTo("vuser");
		assertThat(b.get("createdAt").isNull()).isFalse();
		assertThat(b.get("content").asString()).isEqualTo("후기");
	}

	@Test
	void 조건1_content를_생략하면_빈_문자열로_저장된다() throws Exception {
		String json = "{\"visaType\":\"D_2\",\"applicationType\":\"NEW\",\"receivedDate\":\"2026-09-01\","
				+ "\"resultDate\":\"2026-09-02\",\"result\":\"DENIED\"}";
		MockHttpServletResponse res = 작성(json, 쿠키, true);
		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(json(res).get("content").asString()).isEmpty();
		assertThat(jdbc.queryForObject("select content from visa_reviews", String.class)).isEmpty();
	}

	// ============ 조건 2: 인증·CSRF ============

	@Test
	void 조건2_비로그인은_401이고_저장되지_않는다() throws Exception {
		assertThat(작성(본문("2026-09-01", "2026-10-03"), null, true).getStatus()).isEqualTo(401);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건2_CSRF가_없으면_403이다() throws Exception {
		assertThat(작성(본문("2026-09-01", "2026-10-03"), 쿠키, false).getStatus()).isEqualTo(403);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건2_학교_미인증_회원은_403이다() throws Exception {
		User 미인증 = TestLogin.사용자(userRepository, "noverify");
		Cookie c = TestLogin.쿠키(tokenService, 미인증);
		assertThat(작성(본문("2026-09-01", "2026-10-03"), c, true).getStatus()).isEqualTo(403);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건2_행_없는_회원_토큰은_401이다() throws Exception {
		assertThat(작성(본문("2026-09-01", "2026-10-03"), TestLogin.행없는_회원_토큰(jwtEncoder), true).getStatus()).isEqualTo(401);
		assertThat(후기수()).isZero();
	}

	// ============ 조건 3: schoolId 무시 ============

	@Test
	void 조건3_본문에_고려대_schoolId를_넣어도_연세대로_저장된다() throws Exception {
		long 고려대 = TestLogin.학교(schoolRepository, "고려대학교").getId();
		String json = 본문("2026-09-01", "2026-10-03").replace("{", "{\"schoolId\":" + 고려대 + ",");
		MockHttpServletResponse res = 작성(json, 쿠키, true);
		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(json(res).get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(jdbc.queryForObject("select s.name from visa_reviews v join schools s on s.id = v.school_id", String.class))
				.isEqualTo("연세대학교");
	}

	// ============ 조건 4: 400 ============

	@Test
	void 조건4_필수값이_하나씩_빠지면_400이다() throws Exception {
		String[] 필드 = { "visaType", "applicationType", "result", "receivedDate", "resultDate" };
		for (String f : 필드) {
			JsonNode n = om.readTree(본문("2026-09-01", "2026-10-03"));
			((ObjectNode) n).remove(f);
			assertThat(작성상태(n.toString())).as("%s 누락", f).isEqualTo(400);
		}
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건4_모르는_코드_문자열_F_2는_400이다() throws Exception {
		assertThat(작성상태(본문("2026-09-01", "2026-10-03").replace("D_2", "F_2"))).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건4_숫자_enum은_400이다() throws Exception {
		assertThat(작성상태(본문("2026-09-01", "2026-10-03").replace("\"visaType\":\"D_2\"", "\"visaType\":0"))).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건4_결과일이_접수일보다_빠르면_400이다() throws Exception {
		assertThat(작성상태(본문("2026-10-03", "2026-10-02"))).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건4_접수일이_오늘_플러스_1이면_400이다() throws Exception {
		// 오늘 = 서울 2026-11-09 이므로 접수일 11-10은 미래(결과일도 11-10이라 순서 규칙은 만족)
		assertThat(작성상태(본문("2026-11-10", "2026-11-10"))).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건4_결과일이_오늘_플러스_1이면_400이다() throws Exception {
		assertThat(작성상태(본문("2026-11-09", "2026-11-10"))).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건4_content_2001자는_400이고_2000자는_201이다() throws Exception {
		assertThat(작성상태(본문("2026-09-01", "2026-10-03").replace("후기", "a".repeat(2001)))).isEqualTo(400);
		assertThat(작성상태(본문("2026-09-01", "2026-10-03").replace("후기", "a".repeat(2000)))).isEqualTo(201);
	}

	@Test
	void 조건4_content에_NUL_문자가_있으면_400이다() throws Exception {
		assertThat(작성상태(본문("2026-09-01", "2026-10-03").replace("후기", "a\\u0000b"))).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	@Test
	void 조건4_결과일과_접수일이_모두_오늘이면_201이다_경계() throws Exception {
		assertThat(작성상태(본문("2026-11-09", "2026-11-09"))).isEqualTo(201);
		assertThat(후기수()).isEqualTo(1);
	}

	// ============ 조건 5: 검사 순서 ============

	@Test
	void 조건5_미인증_회원이_날짜가_틀린_본문을_보내면_400이_아니라_403이다() throws Exception {
		User 미인증 = TestLogin.사용자(userRepository, "noverify");
		Cookie c = TestLogin.쿠키(tokenService, 미인증);
		assertThat(작성(본문("2026-10-03", "2026-10-02"), c, true).getStatus()).isEqualTo(403);
	}

	// ============ 조건 6: 서울 기준 "오늘" ============

	@Test
	void 조건6_UTC_11월9일_16시_서울_11월10일_1시면_결과일_11월10일은_201_11월11일은_400이다() throws Exception {
		시계고정("2026-11-09T16:00:00Z");
		assertThat(작성상태(본문("2026-11-10", "2026-11-10"))).isEqualTo(201);
		assertThat(작성상태(본문("2026-11-10", "2026-11-11"))).isEqualTo(400);
	}

	@Test
	void 조건6_실제_ClockConfig_시계의_존은_Asia_Seoul이다() {
		// 가짜가 아닌 설정 클래스가 만드는 진짜 시계가 서울 존인지 직접 확인한다(UTC 서버에서도 서울 날짜가 되도록)
		Clock real = org.springframework.test.util.ReflectionTestUtils.invokeMethod(new ClockConfig(), "clock"); // clock()이 package-private라 리플렉션으로 호출
		assertThat(real.getZone()).isEqualTo(ZoneId.of("Asia/Seoul"));
	}

	// ============ 조건 7: 탈퇴 경쟁 ============

	@Test
	void 조건7_회원_조회_뒤_저장_전에_탈퇴가_커밋되면_401이고_후기가_안_생긴다() throws Exception {
		Long uid = 회원.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from users where id = ? for update", Long.class, uid),
				() -> 작성상태(본문("2026-09-01", "2026-10-03")),
				() -> jdbc.update("delete from users where id = ?", uid));
		assertThat(status).isEqualTo(401);
		assertThat(후기수()).isZero();
	}

	// ============ 조건 8: 단건 조회 ============

	@Test
	void 조건8_비로그인도_단건_조회가_200이고_응답_모양이_맞다() throws Exception {
		long id = 후기저장(회원, "연세대학교");
		MockHttpServletResponse res = 조회("" + id, null);
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("id").asLong()).isEqualTo(id);
		assertThat(b.get("author").get("nickname").asString()).isEqualTo("vuser");
		assertThat(b.get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(b.get("visaType").get("code").asString()).isEqualTo("D_2");
		assertThat(b.get("processingDays").asLong()).isEqualTo(32);
		assertThat(b.get("content").asString()).isEqualTo("시드");
	}

	@Test
	void 조건8_만료_변조_쿠키가_붙어도_200이다() throws Exception {
		long id = 후기저장(회원, "연세대학교");
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(String.valueOf(회원.getId())).claim("username", "x")
				.issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(2700)).build();
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		assertThat(조회("" + id, new Cookie("access_token", expired)).getStatus()).isEqualTo(200);
		assertThat(조회("" + id, new Cookie("access_token", 쿠키.getValue() + "x")).getStatus()).isEqualTo(200);
	}

	@Test
	void 조건8_없는_id는_404이고_숫자가_아닌_id는_400이다() throws Exception {
		assertThat(조회("999999999", null).getStatus()).isEqualTo(404);
		assertThat(조회("abc", null).getStatus()).isEqualTo(400);
	}

	// ============ 조건 9: 쿼리 수 ============

	@Test
	void 조건9_단건_조회는_작성자와_학교를_같이_읽어_쿼리가_1번만_나간다() throws Exception {
		long id = 후기저장(회원, "연세대학교");
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			MockHttpServletResponse res = 조회("" + id, null);
			assertThat(res.getStatus()).isEqualTo(200);
			assertThat(json(res).get("author").get("nickname").asString()).isEqualTo("vuser");
			assertThat(stats.getPrepareStatementCount()).as("단건 조회 쿼리 수(작성자·학교 N+1 아님)").isEqualTo(1);
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	// ============ 조건 10: 탈퇴 연동 ============

	@Test
	void 조건10_후기를_쓴_회원이_탈퇴하면_204이고_후기는_남고_author는_null_학교는_그대로다() throws Exception {
		long id = 후기저장(회원, "연세대학교");
		// 탈퇴는 비밀번호 확인이 필요하다. 도우미 회원의 비밀번호는 {noop}unused-password 라서 "unused-password"로 확인한다
		int status = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(204);
		assertThat(userRepository.findById(회원.getId())).isEmpty();
		assertThat(후기수()).isEqualTo(1);

		MockHttpServletResponse res = 조회("" + id, null);
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("author").isNull()).isTrue();
		assertThat(b.get("school").get("name").asString()).isEqualTo("연세대학교");
	}
}
