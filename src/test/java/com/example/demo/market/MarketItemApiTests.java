package com.example.demo.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Instant;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S13-a 완료 조건 검사: 물건 글 올리기(POST /market-items), 단건 조회(GET /market-items/{id}), 탈퇴 연동, DB CHECK.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired EntityManagerFactory emf;
	@Autowired PlatformTransactionManager txManager;

	User 회원; // 연세대 인증 회원
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "muser", "연세대학교");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
	}

	@AfterEach
	void 정리() {
		// 물건 글이 회원보다 먼저(FK 순서)
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private static String 본문(String title, String content, String tradeType, String priceJson) {
		return "{\"title\":" + quote(title) + ",\"content\":" + quote(content) + ",\"tradeType\":" + tradeType
				+ (priceJson == null ? "" : ",\"price\":" + priceJson) + "}";
	}

	private static String quote(String s) {
		return "\"" + s + "\"";
	}

	private static String 판매(String price) {
		return 본문("책상", "튼튼한 책상", "\"SALE\"", price);
	}

	private static String 나눔(String price) {
		return 본문("책상", "튼튼한 책상", "\"FREE\"", price);
	}

	private MockHttpServletResponse 작성(String json, Cookie c, boolean withCsrf) throws Exception {
		MockHttpServletRequestBuilder b = post("/market-items").contentType(MediaType.APPLICATION_JSON).content(json);
		if (c != null) {
			b.cookie(c);
		}
		if (withCsrf) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private int 상태(String json) throws Exception {
		return 작성(json, 쿠키, true).getStatus();
	}

	private JsonNode json(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	private MockHttpServletResponse 조회(String idPart) throws Exception {
		return mockMvc.perform(get("/market-items/" + idPart)).andReturn().getResponse();
	}

	private long 글수() {
		return jdbc.queryForObject("select count(*) from market_items", Long.class);
	}

	private long 학교id(String name) {
		return jdbc.queryForObject("select id from schools where name = ?", Long.class, name);
	}

	// ============ 조건 1: 성공 응답 ============

	@Test
	void 조건1_인증_회원이_판매_글을_올리면_201과_Location과_응답_모양이_맞다() throws Exception {
		MockHttpServletResponse res = 작성(판매("5000"), 쿠키, true);
		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode b = json(res);
		long id = b.get("id").asLong();
		assertThat(res.getHeader("Location")).isEqualTo("/market-items/" + id);
		assertThat(b.get("status").get("code").asString()).isEqualTo("ON_SALE");
		assertThat(b.get("status").get("name").asString()).isNotBlank();
		assertThat(b.get("tradeType").get("code").asString()).isEqualTo("SALE");
		assertThat(b.get("price").asInt()).isEqualTo(5000);
		assertThat(b.get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(b.get("seller").get("id").asLong()).isEqualTo(회원.getId());
		assertThat(b.get("seller").get("nickname").asString()).isEqualTo("muser");
		assertThat(글수()).isEqualTo(1);
	}

	// ============ 조건 2: 나눔 가격 ============

	@Test
	void 조건2_나눔은_가격이_없거나_0이면_201이고_응답_가격은_0이다() throws Exception {
		MockHttpServletResponse 없음 = 작성(나눔(null), 쿠키, true);
		assertThat(없음.getStatus()).isEqualTo(201);
		assertThat(json(없음).get("price").asInt()).isZero();
		MockHttpServletResponse 영 = 작성(나눔("0"), 쿠키, true);
		assertThat(영.getStatus()).isEqualTo(201);
		assertThat(json(영).get("price").asInt()).isZero();
	}

	@Test
	void 조건2_나눔인데_가격이_1_이상이면_400이다() throws Exception {
		assertThat(상태(나눔("1"))).isEqualTo(400);
		assertThat(상태(나눔("500"))).isEqualTo(400);
		assertThat(글수()).isZero();
	}

	// ============ 조건 3: 입력 검증 ============

	@Test
	void 조건3_판매인데_가격이_없거나_0이거나_음수이거나_상한을_넘으면_400이다() throws Exception {
		assertThat(상태(판매(null))).isEqualTo(400);
		assertThat(상태(판매("0"))).isEqualTo(400);
		assertThat(상태(판매("-1"))).isEqualTo(400);
		assertThat(상태(판매("10000001"))).isEqualTo(400);
		assertThat(글수()).isZero();
	}

	@Test
	void 조건3_판매_가격_경계값_1과_10000000은_201이다() throws Exception {
		assertThat(상태(판매("1"))).isEqualTo(201);
		assertThat(상태(판매("10000000"))).isEqualTo(201);
	}

	@Test
	void 조건3_제목이_비었거나_101자면_400이고_100자는_201이다() throws Exception {
		assertThat(상태(본문("", "내용", "\"SALE\"", "1000"))).isEqualTo(400);
		assertThat(상태(본문("   ", "내용", "\"SALE\"", "1000"))).isEqualTo(400);
		assertThat(상태(본문("가".repeat(101), "내용", "\"SALE\"", "1000"))).isEqualTo(400);
		assertThat(상태(본문("가".repeat(100), "내용", "\"SALE\"", "1000"))).isEqualTo(201);
	}

	@Test
	void 조건3_내용이_비었거나_2001자면_400이고_2000자는_201이다() throws Exception {
		assertThat(상태(본문("제목", "", "\"SALE\"", "1000"))).isEqualTo(400);
		assertThat(상태(본문("제목", "나".repeat(2001), "\"SALE\"", "1000"))).isEqualTo(400);
		assertThat(상태(본문("제목", "나".repeat(2000), "\"SALE\"", "1000"))).isEqualTo(201);
	}

	@Test
	void 조건3_없는_tradeType이거나_빠져_있으면_400이다() throws Exception {
		assertThat(상태(본문("제목", "내용", "\"BAD\"", "1000"))).isEqualTo(400);
		assertThat(상태("{\"title\":\"제목\",\"content\":\"내용\",\"price\":1000}")).isEqualTo(400);
	}

	// ============ 조건 4: 인증·CSRF ============

	@Test
	void 조건4_비로그인은_401이다() throws Exception {
		assertThat(작성(판매("5000"), null, true).getStatus()).isEqualTo(401);
		assertThat(글수()).isZero();
	}

	@Test
	void 조건4_학교_미인증_회원은_403이다() throws Exception {
		User 미인증 = TestLogin.사용자(userRepository, "noverify");
		Cookie c = TestLogin.쿠키(tokenService, 미인증);
		assertThat(작성(판매("5000"), c, true).getStatus()).isEqualTo(403);
		assertThat(글수()).isZero();
	}

	@Test
	void 조건4_CSRF_토큰이_없으면_403이다() throws Exception {
		assertThat(작성(판매("5000"), 쿠키, false).getStatus()).isEqualTo(403);
		assertThat(글수()).isZero();
	}

	// ============ 조건 5: 서버가 정하는 값 ============

	@Test
	void 조건5_본문에_schoolId_status_sellerId를_넣어도_무시된다() throws Exception {
		User 남 = TestLogin.인증된_사용자(userRepository, jdbc, "other", "고려대학교");
		long 다른학교 = 학교id("고려대학교");
		String json = "{\"title\":\"제목\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":3000,"
				+ "\"schoolId\":" + 다른학교 + ",\"status\":\"DONE\",\"sellerId\":" + 남.getId() + "}";
		MockHttpServletResponse res = 작성(json, 쿠키, true);
		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode b = json(res);
		assertThat(b.get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(b.get("status").get("code").asString()).isEqualTo("ON_SALE");
		assertThat(b.get("seller").get("id").asLong()).isEqualTo(회원.getId());
		// DB에 실제 저장된 값도 확인
		long id = b.get("id").asLong();
		assertThat(jdbc.queryForObject("select seller_id from market_items where id = ?", Long.class, id)).isEqualTo(회원.getId());
		assertThat(jdbc.queryForObject("select school_id from market_items where id = ?", Long.class, id)).isEqualTo(학교id("연세대학교"));
		assertThat(jdbc.queryForObject("select status from market_items where id = ?", String.class, id)).isEqualTo("ON_SALE");
	}

	// ============ 조건 6: 단건 조회 ============

	@Test
	void 조건6_비로그인도_단건_조회가_200이다() throws Exception {
		long id = json(작성(판매("5000"), 쿠키, true)).get("id").asLong();
		MockHttpServletResponse res = 조회("" + id);
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("id").asLong()).isEqualTo(id);
		assertThat(b.get("title").asString()).isEqualTo("책상");
		assertThat(b.get("seller").get("nickname").asString()).isEqualTo("muser");
	}

	@Test
	void 조건6_없는_id는_404이고_숫자가_아닌_id는_400이다() throws Exception {
		assertThat(조회("999999999").getStatus()).isEqualTo(404);
		assertThat(조회("abc").getStatus()).isEqualTo(400);
	}

	// ============ 조건 7: 탈퇴 연동 ============

	@Test
	void 조건7_판매자가_탈퇴하면_그_글_조회는_404이다() throws Exception {
		long id = json(작성(판매("5000"), 쿠키, true)).get("id").asLong();
		assertThat(조회("" + id).getStatus()).isEqualTo(200);
		// 탈퇴는 비밀번호 확인이 필요하다(도우미 회원 비밀번호는 unused-password)
		int status = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(204);
		assertThat(조회("" + id).getStatus()).isEqualTo(404);
		assertThat(글수()).isZero();
	}

	// ============ 조건 8: DB CHECK ============

	private int 직접삽입(String tradeType, int price, String status) {
		return jdbc.update("insert into market_items (seller_id, school_id, title, content, trade_type, price, status, created_at) "
				+ "values (?, ?, 't', 'c', ?, ?, ?, now())", 회원.getId(), 학교id("연세대학교"), tradeType, price, status);
	}

	@Test
	void 조건8_DB가_나눔에_가격_500을_거부한다() {
		assertThatThrownBy(() -> 직접삽입("FREE", 500, "ON_SALE")).hasMessageContaining("ck_market_items_price");
	}

	@Test
	void 조건8_DB가_판매에_가격_0을_거부한다() {
		assertThatThrownBy(() -> 직접삽입("SALE", 0, "ON_SALE")).hasMessageContaining("ck_market_items_price");
	}

	@Test
	void 조건8_DB가_판매_가격_상한_초과와_모르는_값을_거부하고_정상_행은_받는다() {
		assertThatThrownBy(() -> 직접삽입("SALE", 10_000_001, "ON_SALE")).hasMessageContaining("ck_market_items_price");
		// 모르는 거래 방식은 가격 규칙 CHECK에도 걸리므로 어느 CHECK든 거부되면 된다
		assertThatThrownBy(() -> 직접삽입("BAD", 0, "ON_SALE")).hasMessageContaining("ck_market_items_");
		assertThatThrownBy(() -> 직접삽입("SALE", 100, "BAD")).hasMessageContaining("ck_market_items_status");
		assertThat(직접삽입("FREE", 0, "ON_SALE")).isEqualTo(1);
		assertThat(직접삽입("SALE", 10_000_000, "DONE")).isEqualTo(1);
	}

	// ============ 리뷰 권장 테스트 4개 ============

	@Test
	void 권장1_회원_행이_잠긴_사이_탈퇴가_커밋되면_401이고_물건_글이_안_생긴다() throws Exception {
		// 무엇: 회원 조회 뒤 저장 전에 탈퇴가 끼어드는 경쟁 / 왜: seller FK 위반을 500이 아닌 401로 바꾸는지 확인
		Long uid = 회원.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from users where id = ? for update", Long.class, uid),
				() -> 상태(판매("5000")),
				() -> jdbc.update("delete from users where id = ?", uid));
		assertThat(status).isEqualTo(401);
		assertThat(글수()).isZero();
	}

	@Test
	void 권장2_제목이나_내용에_NUL_문자가_있으면_400이다() throws Exception {
		// 무엇: NUL 문자가 든 입력 / 왜: PostgreSQL이 NUL을 저장 못 해 500이 나는 것을 막고 400으로 거절하는지 확인
		assertThat(상태(본문("a\\u0000b", "내용", "\"SALE\"", "1000"))).isEqualTo(400);
		assertThat(상태(본문("제목", "a\\u0000b", "\"SALE\"", "1000"))).isEqualTo(400);
		assertThat(글수()).isZero();
	}

	@Test
	void 권장3_만료_변조_쿠키가_붙어도_단건_조회는_200이다() throws Exception {
		// 무엇: 나쁜 로그인 쿠키가 붙은 공개 조회 / 왜: 공개 경로는 쿠키를 읽지 않아 막히면 안 되기 때문
		long id = json(작성(판매("5000"), 쿠키, true)).get("id").asLong();
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(String.valueOf(회원.getId())).claim("username", "x")
				.issuedAt(now.minusSeconds(3600)).expiresAt(now.minusSeconds(2700)).build();
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		MockHttpServletRequestBuilder 만료 = get("/market-items/" + id).cookie(new Cookie("access_token", expired));
		MockHttpServletRequestBuilder 변조 = get("/market-items/" + id).cookie(new Cookie("access_token", 쿠키.getValue() + "x"));
		assertThat(mockMvc.perform(만료).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(변조).andReturn().getResponse().getStatus()).isEqualTo(200);
	}

	@Test
	void 권장4_단건_조회는_판매자와_학교를_같이_읽어_쿼리가_1번만_나간다() throws Exception {
		// 무엇: 한 건 조회의 SQL 수 / 왜: 판매자·학교를 따로 읽는 N+1이 생기지 않았는지 확인
		long id = json(작성(판매("5000"), 쿠키, true)).get("id").asLong();
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			MockHttpServletResponse res = 조회("" + id);
			assertThat(res.getStatus()).isEqualTo(200);
			assertThat(json(res).get("seller").get("nickname").asString()).isEqualTo("muser");
			assertThat(stats.getPrepareStatementCount()).as("단건 조회 쿼리 수").isEqualTo(1);
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}
}
