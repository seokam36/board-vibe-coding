package com.example.demo.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

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

// S13-b 완료 조건 검사: 물건 글 목록(필터·N+1), 수정(PUT), 삭제(DELETE).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemListUpdateDeleteTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired EntityManagerFactory emf;
	@Autowired PlatformTransactionManager txManager;

	User 회원; // 연세대 인증 판매자
	User 남; // 고려대 인증 다른 회원
	User 관리자;
	Cookie 쿠키;
	Cookie 남쿠키;
	Cookie 관리자쿠키;

	@BeforeEach
	void 준비() {
		정리();
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "muser", "연세대학교");
		남 = TestLogin.인증된_사용자(userRepository, jdbc, "other", "고려대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "madmin");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		남쿠키 = TestLogin.쿠키(tokenService, 남);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private static String 본문(String title, String content, String tradeType, String price) {
		return "{\"title\":\"" + title + "\",\"content\":\"" + content + "\",\"tradeType\":\"" + tradeType + "\""
				+ (price == null ? "" : ",\"price\":" + price) + "}";
	}

	private long 올리기(Cookie c, String title, String tradeType, String price) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(본문(title, "내용", tradeType, price)).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private long 학교id(String name) {
		return jdbc.queryForObject("select id from schools where name = ?", Long.class, name);
	}

	private MockHttpServletResponse 목록(String query) throws Exception {
		return mockMvc.perform(get("/market-items" + query)).andReturn().getResponse();
	}

	// 200이고 JSON 배열임을 확인한 뒤 id 목록을 돌려준다(깨진 응답이 가짜 통과하지 않게)
	private List<Long> 목록ids(String query) throws Exception {
		MockHttpServletResponse r = 목록(query);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode arr = om.readTree(r.getContentAsString());
		assertThat(arr.isArray()).isTrue();
		List<Long> ids = new ArrayList<>();
		for (JsonNode n : arr) {
			ids.add(n.get("id").asLong());
		}
		return ids;
	}

	private MockHttpServletResponse 수정(long id, String json, Cookie c, boolean withCsrf) throws Exception {
		MockHttpServletRequestBuilder b = put("/market-items/" + id).contentType(MediaType.APPLICATION_JSON).content(json);
		if (c != null) {
			b.cookie(c);
		}
		if (withCsrf) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private int 삭제(long id, Cookie c, boolean withCsrf) throws Exception {
		MockHttpServletRequestBuilder b = delete("/market-items/" + id);
		if (c != null) {
			b.cookie(c);
		}
		if (withCsrf) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse().getStatus();
	}

	private int 단건상태(long id) throws Exception {
		return mockMvc.perform(get("/market-items/" + id)).andReturn().getResponse().getStatus();
	}

	private void 상태바꾸기(long id, String status) {
		jdbc.update("update market_items set status = ? where id = ?", status, id);
	}

	// ============ 조건 1: 목록 기본 ============

	@Test
	void 조건1_비로그인도_목록이_200이고_최신순이다() throws Exception {
		// 무엇: 로그인 없이 목록 / 왜: 공개 API이고 id 내림차순이어야 해서
		long a = 올리기(쿠키, "첫째", "SALE", "1000");
		long b = 올리기(남쿠키, "둘째", "FREE", null);
		long c = 올리기(쿠키, "셋째", "SALE", "2000");
		assertThat(목록ids("")).containsExactly(c, b, a);
	}

	@Test
	void 조건1_글이_없으면_빈_배열이다() throws Exception {
		MockHttpServletResponse r = 목록("");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentAsString()).isEqualTo("[]");
	}

	@Test
	void 조건1_목록_항목에_판매자와_학교와_코드가_들어_있다() throws Exception {
		올리기(쿠키, "책상", "SALE", "5000");
		JsonNode first = om.readTree(목록("").getContentAsString()).get(0);
		assertThat(first.get("title").asString()).isEqualTo("책상");
		assertThat(first.get("seller").get("nickname").asString()).isEqualTo("muser");
		assertThat(first.get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(first.get("tradeType").get("code").asString()).isEqualTo("SALE");
		assertThat(first.get("status").get("code").asString()).isEqualTo("ON_SALE");
	}

	// ============ 조건 2: 필터 ============

	@Test
	void 조건2_schoolId_필터는_그_학교_글만_준다() throws Exception {
		long y = 올리기(쿠키, "연세", "SALE", "1000");
		long k = 올리기(남쿠키, "고려", "SALE", "1000");
		assertThat(목록ids("?schoolId=" + 학교id("연세대학교"))).containsExactly(y);
		assertThat(목록ids("?schoolId=" + 학교id("고려대학교"))).containsExactly(k);
	}

	@Test
	void 조건2_tradeType_필터는_그_종류만_준다() throws Exception {
		long s = 올리기(쿠키, "판매", "SALE", "1000");
		long f = 올리기(쿠키, "나눔", "FREE", null);
		assertThat(목록ids("?tradeType=SALE")).containsExactly(s);
		assertThat(목록ids("?tradeType=FREE")).containsExactly(f);
	}

	@Test
	void 조건2_status_필터는_그_상태만_준다() throws Exception {
		long on = 올리기(쿠키, "판매중", "SALE", "1000");
		long done = 올리기(쿠키, "완료", "SALE", "1000");
		상태바꾸기(done, "DONE");
		assertThat(목록ids("?status=ON_SALE")).containsExactly(on);
		assertThat(목록ids("?status=DONE")).containsExactly(done);
	}

	@Test
	void 조건2_세_필터를_같이_쓰면_모두_맞는_글만_준다() throws Exception {
		long target = 올리기(쿠키, "목표", "SALE", "1000");
		long 다른종류 = 올리기(쿠키, "나눔", "FREE", null);
		long 다른상태 = 올리기(쿠키, "완료", "SALE", "1000");
		상태바꾸기(다른상태, "DONE");
		long 다른학교 = 올리기(남쿠키, "고려", "SALE", "1000");
		String q = "?schoolId=" + 학교id("연세대학교") + "&tradeType=SALE&status=ON_SALE";
		assertThat(목록ids(q)).containsExactly(target);
		assertThat(목록ids("")).containsExactlyInAnyOrder(target, 다른종류, 다른상태, 다른학교);
	}

	@Test
	void 조건2_빈_값은_조건_없음이다() throws Exception {
		long a = 올리기(쿠키, "판매", "SALE", "1000");
		long b = 올리기(쿠키, "나눔", "FREE", null);
		상태바꾸기(b, "DONE");
		assertThat(목록ids("?status=")).containsExactly(b, a);
		assertThat(목록ids("?tradeType=&status=&schoolId=")).containsExactly(b, a);
	}

	// ============ 조건 3: 잘못된 필터 ============

	@Test
	void 조건3_없는_schoolId는_404다() throws Exception {
		올리기(쿠키, "책상", "SALE", "1000");
		assertThat(목록("?schoolId=999999999").getStatus()).isEqualTo(404);
	}

	@Test
	void 조건3_숫자가_아닌_schoolId와_없는_tradeType_status는_400이다() throws Exception {
		올리기(쿠키, "책상", "SALE", "1000");
		assertThat(목록("?schoolId=abc").getStatus()).isEqualTo(400);
		assertThat(목록("?tradeType=BAD").getStatus()).isEqualTo(400);
		assertThat(목록("?status=BAD").getStatus()).isEqualTo(400);
	}

	// ============ 조건 4: N+1 없음 ============

	private long 목록_쿼리수() throws Exception {
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			assertThat(목록ids("")).isNotEmpty();
			return stats.getPrepareStatementCount();
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	@Test
	void 조건4_목록_쿼리_수는_글_수와_무관하다() throws Exception {
		// 무엇: 글 1개/5개일 때 SQL 수 비교 / 왜: 판매자·학교를 글마다 따로 읽는 N+1이 없는지 확인(판매자 여러 명으로)
		올리기(쿠키, "하나", "SALE", "1000");
		long 하나일때 = 목록_쿼리수();
		올리기(남쿠키, "둘", "SALE", "1000");
		올리기(쿠키, "셋", "FREE", null);
		올리기(남쿠키, "넷", "SALE", "1000");
		올리기(쿠키, "다섯", "SALE", "1000");
		long 다섯일때 = 목록_쿼리수();
		assertThat(다섯일때).as("글 1개 vs 5개 쿼리 수").isEqualTo(하나일때);
		assertThat(하나일때).isLessThanOrEqualTo(2);
	}

	// ============ 조건 5: 수정 성공 ============

	private static final String 새본문판매 = 본문("새제목", "새내용", "SALE", "7000");

	@Test
	void 조건5_본인이_수정하면_200이고_제목_본문_종류_가격이_바뀐다() throws Exception {
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		MockHttpServletResponse r = 수정(id, 새본문판매, 쿠키, true);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode b = om.readTree(r.getContentAsString());
		assertThat(b.get("id").asLong()).isEqualTo(id);
		assertThat(b.get("title").asString()).isEqualTo("새제목");
		assertThat(b.get("content").asString()).isEqualTo("새내용");
		assertThat(b.get("price").asInt()).isEqualTo(7000);
		// DB에 실제로 저장됐는지
		assertThat(jdbc.queryForObject("select title from market_items where id = ?", String.class, id)).isEqualTo("새제목");
		assertThat(jdbc.queryForObject("select price from market_items where id = ?", Integer.class, id)).isEqualTo(7000);
	}

	@Test
	void 조건5_판매에서_나눔으로_바꾸면_가격이_0이다() throws Exception {
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		MockHttpServletResponse r = 수정(id, 본문("책상", "내용", "FREE", "0"), 쿠키, true);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode b = om.readTree(r.getContentAsString());
		assertThat(b.get("tradeType").get("code").asString()).isEqualTo("FREE");
		assertThat(b.get("price").asInt()).isZero();
		assertThat(jdbc.queryForObject("select trade_type from market_items where id = ?", String.class, id)).isEqualTo("FREE");
	}

	@Test
	void 조건5_나눔에서_판매로_바꾸면_가격이_1000이다() throws Exception {
		long id = 올리기(쿠키, "책상", "FREE", null);
		MockHttpServletResponse r = 수정(id, 본문("책상", "내용", "SALE", "1000"), 쿠키, true);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode b = om.readTree(r.getContentAsString());
		assertThat(b.get("tradeType").get("code").asString()).isEqualTo("SALE");
		assertThat(b.get("price").asInt()).isEqualTo(1000);
		assertThat(jdbc.queryForObject("select price from market_items where id = ?", Integer.class, id)).isEqualTo(1000);
	}

	@Test
	void 조건5_수정해도_상태_학교_판매자는_그대로이고_본문의_status_schoolId는_무시된다() throws Exception {
		// 무엇: 판매중 글을 고치며 본문에 status/schoolId/sellerId를 끼워 보냄 / 왜: 내용 수정으로는 이 값들이 안 바뀌어야 해서
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		String json = "{\"title\":\"새제목\",\"content\":\"새내용\",\"tradeType\":\"SALE\",\"price\":2000,"
				+ "\"schoolId\":" + 학교id("고려대학교") + ",\"status\":\"DONE\",\"sellerId\":" + 남.getId() + "}";
		MockHttpServletResponse r = 수정(id, json, 쿠키, true);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode b = om.readTree(r.getContentAsString());
		assertThat(b.get("status").get("code").asString()).isEqualTo("ON_SALE");
		assertThat(b.get("school").get("name").asString()).isEqualTo("연세대학교");
		assertThat(b.get("seller").get("id").asLong()).isEqualTo(회원.getId());
		assertThat(jdbc.queryForObject("select status from market_items where id = ?", String.class, id)).isEqualTo("ON_SALE");
		assertThat(jdbc.queryForObject("select school_id from market_items where id = ?", Long.class, id)).isEqualTo(학교id("연세대학교"));
		assertThat(jdbc.queryForObject("select seller_id from market_items where id = ?", Long.class, id)).isEqualTo(회원.getId());
	}

	@Test
	void 조건5_거래완료_글_수정은_409이고_내용_상태는_그대로다() throws Exception {
		// 무엇: DONE 글에 PUT / 왜: 사용자 결정(완료된 글은 수정 막음)에 따라 409, 제목·가격·상태가 안 바뀌어야 해서
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		상태바꾸기(id, "DONE");
		assertThat(수정(id, 새본문판매, 쿠키, true).getStatus()).isEqualTo(409);
		assertThat(제목(id)).isEqualTo("책상");
		assertThat(jdbc.queryForObject("select price from market_items where id = ?", Integer.class, id)).isEqualTo(1000);
		assertThat(jdbc.queryForObject("select status from market_items where id = ?", String.class, id)).isEqualTo("DONE");
	}

	// ============ 조건 6: 수정 거절 ============

	private String 제목(long id) {
		return jdbc.queryForObject("select title from market_items where id = ?", String.class, id);
	}

	@Test
	void 조건6_남의_글_수정은_403이고_글은_안_바뀐다() throws Exception {
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		assertThat(수정(id, 새본문판매, 남쿠키, true).getStatus()).isEqualTo(403);
		assertThat(제목(id)).isEqualTo("옛제목");
	}

	@Test
	void 조건6_관리자도_남의_글_수정은_403이다() throws Exception {
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		assertThat(수정(id, 새본문판매, 관리자쿠키, true).getStatus()).isEqualTo(403);
		assertThat(제목(id)).isEqualTo("옛제목");
	}

	@Test
	void 조건6_없는_id_수정은_404다() throws Exception {
		assertThat(수정(999999999L, 새본문판매, 쿠키, true).getStatus()).isEqualTo(404);
	}

	@Test
	void 조건6_가격_규칙_위반과_빈_제목은_400이고_글은_안_바뀐다() throws Exception {
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		assertThat(수정(id, 본문("새", "내용", "SALE", null), 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(수정(id, 본문("새", "내용", "SALE", "0"), 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(수정(id, 본문("새", "내용", "FREE", "500"), 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(수정(id, 본문("", "내용", "SALE", "1000"), 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(제목(id)).isEqualTo("옛제목");
		assertThat(jdbc.queryForObject("select price from market_items where id = ?", Integer.class, id)).isEqualTo(1000);
	}

	@Test
	void 조건6_비로그인은_401이고_CSRF가_없으면_403이다() throws Exception {
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		assertThat(수정(id, 새본문판매, null, true).getStatus()).isEqualTo(401);
		assertThat(수정(id, 새본문판매, 쿠키, false).getStatus()).isEqualTo(403);
		assertThat(제목(id)).isEqualTo("옛제목");
	}

	@Test
	void 조건6_학교_인증을_해제한_판매자도_자기_글_수정은_200이다() throws Exception {
		// 무엇: 인증 해제 후 수정 / 왜: 수정은 학교 인증을 요구하지 않는다(S10 규칙)
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		jdbc.update("update users set verified_school_id = null, verified_at = null, school_email_hash = null where id = ?",
				회원.getId());
		MockHttpServletResponse r = 수정(id, 새본문판매, 쿠키, true);
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(om.readTree(r.getContentAsString()).get("title").asString()).isEqualTo("새제목");
		assertThat(제목(id)).isEqualTo("새제목");
	}

	// ============ 조건 7: 삭제 ============

	@Test
	void 조건7_본인이_삭제하면_204이고_이후_조회는_404다() throws Exception {
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		assertThat(삭제(id, 쿠키, true)).isEqualTo(204);
		assertThat(단건상태(id)).isEqualTo(404);
		assertThat(목록ids("")).doesNotContain(id);
	}

	@Test
	void 조건7_관리자는_남의_글을_삭제할_수_있다_204() throws Exception {
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		assertThat(삭제(id, 관리자쿠키, true)).isEqualTo(204);
		assertThat(단건상태(id)).isEqualTo(404);
	}

	@Test
	void 조건7_다른_회원은_403이고_글은_남는다() throws Exception {
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		assertThat(삭제(id, 남쿠키, true)).isEqualTo(403);
		assertThat(단건상태(id)).isEqualTo(200);
	}

	@Test
	void 조건7_없는_id는_404이고_비로그인은_401이다() throws Exception {
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		assertThat(삭제(999999999L, 쿠키, true)).isEqualTo(404);
		assertThat(삭제(id, null, true)).isEqualTo(401);
		assertThat(단건상태(id)).isEqualTo(200);
	}

	@Test
	void 조건7_CSRF가_없으면_삭제는_403이고_글은_남는다() throws Exception {
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		assertThat(삭제(id, 쿠키, false)).isEqualTo(403);
		assertThat(단건상태(id)).isEqualTo(200);
	}

	// ============ 리뷰 반영 A: 수정 중 삭제 경쟁 ============

	private long 글수(long id) {
		return jdbc.queryForObject("select count(*) from market_items where id = ?", Long.class, id);
	}

	@Test
	void 리뷰A_수정하는_사이_다른_쪽이_글을_지우고_커밋하면_PUT은_404이다() throws Exception {
		// 무엇: PUT이 글을 읽은 뒤 UPDATE 직전에 글이 삭제 커밋된 상황 / 왜: 0행 UPDATE가 500이 아니라 404여야 해서
		// 재현 순서: (1) 다른 트랜잭션이 market_items 행을 FOR UPDATE로 잠금
		//  (2) PUT 스레드 시작: findById(잠금 없는 일반 SELECT라 안 막힘, 글이 보임) -> 본인 확인 통과 -> 조건부 UPDATE(updateContentIfNotDone)가 행 잠금에서 대기
		//  (3) 정말 대기 중인지 확인 (4) 잠근 쪽이 DELETE 하고 커밋 -> 깨어난 UPDATE는 0행 -> existsById가 false라 서비스가 404로 바꿈
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from market_items where id = ? for update", id),
				() -> 수정(id, 새본문판매, 쿠키, true).getStatus(),
				() -> jdbc.update("delete from market_items where id = ?", id));
		assertThat(status).isEqualTo(404);
		assertThat(글수(id)).isZero();
	}

	// ============ 리뷰 반영 B: 묵은 토큰 ============

	@Test
	void 리뷰B_토큰은_관리자인데_DB에서_USER로_강등됐으면_남의_글_삭제는_403이다() throws Exception {
		// 무엇: 강등된 관리자 토큰으로 삭제 / 왜: 토큰 role만 믿지 않고 DB role을 다시 확인해야 해서
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		jdbc.update("update users set role = 'USER' where id = ?", 관리자.getId());
		assertThat(삭제(id, 관리자쿠키, true)).isEqualTo(403);
		assertThat(글수(id)).isEqualTo(1);
	}

	@Test
	void 리뷰B_토큰은_관리자인데_회원_행이_없으면_삭제는_401이다() throws Exception {
		// 무엇: 탈퇴한 관리자 토큰으로 삭제 / 왜: 없는 회원은 인증 실패(401)여야 해서
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		jdbc.update("delete from users where id = ?", 관리자.getId());
		assertThat(삭제(id, 관리자쿠키, true)).isEqualTo(401);
		assertThat(글수(id)).isEqualTo(1);
	}

	@Test
	void 리뷰B_탈퇴한_일반_회원_토큰으로_남의_글_수정은_403이고_글은_안_바뀐다() throws Exception {
		// 무엇: 유령 일반 회원의 PUT / 왜: 글 주인이 아니므로 403이어야 해서
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		jdbc.update("delete from users where id = ?", 남.getId());
		assertThat(수정(id, 새본문판매, 남쿠키, true).getStatus()).isEqualTo(403);
		assertThat(제목(id)).isEqualTo("옛제목");
	}

	@Test
	void 리뷰B_탈퇴한_일반_회원_토큰으로_남의_글_삭제는_403이고_글은_남는다() throws Exception {
		// 무엇: 유령 일반 회원의 DELETE / 왜: 관리자도 주인도 아니므로 403이어야 해서
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		jdbc.update("delete from users where id = ?", 남.getId());
		assertThat(삭제(id, 남쿠키, true)).isEqualTo(403);
		assertThat(글수(id)).isEqualTo(1);
	}

	// ============ 리뷰 반영 C: 동시 삭제 ============

	@Test
	void 리뷰C_두_삭제가_겹치면_하나는_204_하나는_404이다() throws Exception {
		// 무엇: 같은 글을 두 스레드가 동시에 DELETE / 왜: 늦은 쪽이 0행 삭제를 404로 처리해야 해서(순서는 무작위여도 결과 조합은 같다)
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch go = new CountDownLatch(1);
			java.util.concurrent.Callable<Integer> task = () -> {
				ready.countDown();
				go.await();
				return 삭제(id, 쿠키, true);
			};
			Future<Integer> f1 = pool.submit(task);
			Future<Integer> f2 = pool.submit(task);
			ready.await();
			go.countDown();
			List<Integer> statuses = List.of(f1.get(10, TimeUnit.SECONDS), f2.get(10, TimeUnit.SECONDS));
			assertThat(statuses).containsExactlyInAnyOrder(204, 404);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertThat(글수(id)).isZero();
	}

	@Test
	void 리뷰C_다른_쪽이_먼저_지우고_커밋하면_삭제는_404이다() throws Exception {
		// 무엇: 락 대기 중 먼저 삭제 커밋 / 왜: 결정적으로 0행 삭제 -> 404 경로를 확인(동시 테스트가 우연히 순차로 돌았을 가능성 보완)
		long id = 올리기(쿠키, "책상", "SALE", "1000");
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from market_items where id = ? for update", id),
				() -> 삭제(id, 쿠키, true), () -> jdbc.update("delete from market_items where id = ?", id));
		assertThat(status).isEqualTo(404);
	}

	// ============ 리뷰 반영 D: 검사 순서 ============

	@Test
	void 리뷰D_남의_글에_잘못된_가격으로_수정하면_403이_아니라_400이다() throws Exception {
		// 무엇: 남의 글 + 가격 위반 / 왜: 입력 검사(400)가 소유 확인(403)보다 먼저여야 해서
		long id = 올리기(쿠키, "옛제목", "SALE", "1000");
		assertThat(수정(id, 본문("새", "내용", "SALE", "0"), 남쿠키, true).getStatus()).isEqualTo(400);
		assertThat(제목(id)).isEqualTo("옛제목");
	}

	@Test
	void 리뷰D_없는_id에_잘못된_가격으로_수정하면_404가_아니라_400이다() throws Exception {
		// 무엇: 없는 id + 가격 위반 / 왜: 입력 검사(400)가 존재 확인(404)보다 먼저여야 해서
		assertThat(수정(999999999L, 본문("새", "내용", "FREE", "500"), 쿠키, true).getStatus()).isEqualTo(400);
	}
}
