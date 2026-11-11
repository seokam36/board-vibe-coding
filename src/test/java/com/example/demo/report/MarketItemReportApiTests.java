package com.example.demo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.domain.School;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S13-e 중고 글 신고 완료 조건 검사: 신고 API, 관리자 목록·기각, CASCADE, DB 제약(V10)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemReportApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired PlatformTransactionManager txManager;
	@Autowired EntityManagerFactory emf;

	User 판매자; // 연세대 인증
	User 회원A; // 연세대 인증 (신고자)
	User 회원B; // 연세대 인증
	User 고려대회원; // 다른 학교 인증
	User 미인증회원;
	User 관리자;
	Cookie 판매자쿠키, 쿠키A, 쿠키B, 고려쿠키, 미인증쿠키, 관리자쿠키;
	long 글id; // 판매자가 올린 연세대 중고 글

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "mseller", "연세대학교");
		회원A = TestLogin.인증된_사용자(userRepository, jdbc, "mrepA", "연세대학교");
		회원B = TestLogin.인증된_사용자(userRepository, jdbc, "mrepB", "연세대학교");
		고려대회원 = TestLogin.인증된_사용자(userRepository, jdbc, "mkorea", "고려대학교");
		미인증회원 = TestLogin.사용자(userRepository, "munverified");
		관리자 = TestLogin.관리자(userRepository, jdbc, "madmin");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		쿠키A = TestLogin.쿠키(tokenService, 회원A);
		쿠키B = TestLogin.쿠키(tokenService, 회원B);
		고려쿠키 = TestLogin.쿠키(tokenService, 고려대회원);
		미인증쿠키 = TestLogin.쿠키(tokenService, 미인증회원);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		글id = 중고글_올리기(판매자쿠키, "자전거");
	}

	// 중고 글을 지우면 신고도 CASCADE로 지워진다 -> 글, 글(게시판), 회원 순서로 지운다
	@AfterEach
	void 정리() {
		jdbc.update("delete from reports");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private long 중고글_올리기(Cookie c, String title) throws Exception {
		String body = "{\"title\":\"" + title + "\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 중고 글 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private static String 본문(String reason) {
		return "{\"reason\":\"" + reason + "\"}";
	}

	private MockHttpServletResponse 신고(Cookie cookie, Object itemId, String body) throws Exception {
		var req = post("/market-items/" + itemId + "/reports").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(body);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 관리자목록() throws Exception {
		return mockMvc.perform(get("/admin/reports").cookie(관리자쿠키)).andReturn().getResponse();
	}

	private MockHttpServletResponse 기각(Object id) throws Exception {
		return mockMvc.perform(put("/admin/reports/" + id + "/dismiss").with(csrf()).cookie(관리자쿠키)).andReturn()
				.getResponse();
	}

	private JsonNode json(MockHttpServletResponse res) throws Exception {
		return om.readTree(res.getContentAsString());
	}

	private long 신고행수() {
		return jdbc.queryForObject("select count(*) from reports", Long.class);
	}

	// 신고 행을 SQL로 직접 넣는다 (DB 제약 검사용). null이면 SQL NULL
	private void 직접삽입(Long postId, Long commentId, Long marketItemId, Long reporterId) {
		jdbc.update("insert into reports (post_id, comment_id, market_item_id, reporter_id, reason, status, created_at) "
				+ "values (?, ?, ?, ?, 'SPAM', 'PENDING', now())", postId, commentId, marketItemId, reporterId);
	}

	// ============ 조건 1: 성공 ============

	@Test
	void 조건1_남의_중고_글을_신고하면_201과_응답_모양이고_DB에_market_item_id가_채워진다() throws Exception {
		MockHttpServletResponse res = 신고(쿠키A, 글id, "{\"reason\":\"SPAM\",\"detail\":\"광고예요\"}");

		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode b = json(res);
		assertThat(b.get("id").asLong()).isPositive();
		assertThat(b.get("target").get("type").asString()).isEqualTo("MARKET_ITEM");
		assertThat(b.get("target").get("id").asLong()).isEqualTo(글id);
		assertThat(b.get("target").get("postId").isNull()).isTrue();
		assertThat(b.get("reason").get("code").asString()).isEqualTo("SPAM");
		assertThat(b.get("detail").asString()).isEqualTo("광고예요");
		assertThat(b.get("status").asString()).isEqualTo("PENDING");

		// DB 행: 대상은 중고 글 칸만 채워지고 글·댓글 칸은 비어 있다
		var row = jdbc.queryForMap("select * from reports");
		assertThat(((Number) row.get("market_item_id")).longValue()).isEqualTo(글id);
		assertThat(row.get("post_id")).isNull();
		assertThat(row.get("comment_id")).isNull();
		assertThat(((Number) row.get("reporter_id")).longValue()).isEqualTo(회원A.getId());
	}

	// ============ 조건 2: 오류 코드 ============

	@Test
	void 조건2_비로그인으로_중고_글을_신고하면_401이다() throws Exception {
		assertThat(신고(null, 글id, 본문("SPAM")).getStatus()).isEqualTo(401);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건2_유령_토큰이면_401이다() throws Exception {
		assertThat(신고(TestLogin.행없는_회원_토큰(jwtEncoder), 글id, 본문("SPAM")).getStatus()).isEqualTo(401);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건2_학교_미인증_회원은_403이다() throws Exception {
		assertThat(신고(미인증쿠키, 글id, 본문("SPAM")).getStatus()).isEqualTo(403);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건2_다른_학교_중고_글은_403이다() throws Exception {
		assertThat(신고(고려쿠키, 글id, 본문("SPAM")).getStatus()).isEqualTo(403);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건2_없는_중고_글은_404다() throws Exception {
		assertThat(신고(쿠키A, 999999999L, 본문("SPAM")).getStatus()).isEqualTo(404);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건2_자기_중고_글을_신고하면_400과_안내_문구다() throws Exception {
		MockHttpServletResponse res = 신고(판매자쿠키, 글id, 본문("SPAM"));
		assertThat(res.getStatus()).isEqualTo(400);
		// 본문은 비어 있다(서버 설정). 문구는 응답의 오류 메시지 칸에서 확인한다
		assertThat(res.getErrorMessage()).isEqualTo("자기 중고 글은 신고할 수 없습니다.");
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건2_같은_회원이_같은_중고_글을_두_번_신고하면_두번째는_409이고_행은_1개다() throws Exception {
		assertThat(신고(쿠키A, 글id, 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(신고(쿠키A, 글id, 본문("ABUSE")).getStatus()).isEqualTo(409);
		assertThat(신고행수()).isEqualTo(1);
	}

	@Test
	void 조건2_다른_회원은_같은_중고_글을_각각_신고할_수_있다() throws Exception {
		assertThat(신고(쿠키A, 글id, 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(신고(쿠키B, 글id, 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(신고행수()).isEqualTo(2);
	}

	@Test
	void 조건2_사유가_없거나_목록에_없으면_400이고_저장되지_않는다() throws Exception {
		assertThat(신고(쿠키A, 글id, "{}").getStatus()).isEqualTo(400);
		assertThat(신고(쿠키A, 글id, 본문("XXX")).getStatus()).isEqualTo(400);
		assertThat(신고행수()).isZero();
	}

	// 신고 저장 직전에 중고 글 삭제가 커밋되면 fk_reports_market_item 위반 -> 404 (500이면 안 된다)
	@Test
	void 조건2_신고_저장_직전에_중고_글_삭제가_커밋되면_404이고_신고_행이_안_생긴다() throws Exception {
		long id = 글id;
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from market_items where id = ?", id),
				() -> {
					try {
						return 신고(쿠키A, id, 본문("SPAM")).getStatus();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				},
				() -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(신고행수()).isZero();
	}

	// ============ 조건 3: 관리자 목록 ============

	@Test
	void 조건3_관리자_목록에_중고_글_신고가_글_댓글_신고와_섞여_나오고_모양이_맞다() throws Exception {
		// 글 신고 하나(게시판 글)와 중고 글 신고 하나를 섞는다
		School 연세대 = TestLogin.학교(schoolRepository, "연세대학교");
		Post 게시글 = postRepository.save(new Post("게시판 글", "내용", 판매자, 연세대, PostCategory.CHAT));
		mockMvc.perform(post("/posts/" + 게시글.getId() + "/reports").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(본문("SPAM")).cookie(쿠키A)).andReturn();
		assertThat(신고(쿠키A, 글id, "{\"reason\":\"ABUSE\",\"detail\":\"욕설\"}").getStatus()).isEqualTo(201);

		MockHttpServletResponse res = 관리자목록();

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = json(res);
		assertThat(arr.size()).isEqualTo(2);
		assertThat(arr.get(0).get("target").get("type").asString()).isEqualTo("POST");
		JsonNode n = arr.get(1);
		JsonNode t = n.get("target");
		assertThat(t.get("type").asString()).isEqualTo("MARKET_ITEM");
		assertThat(t.get("id").asLong()).isEqualTo(글id);
		assertThat(t.get("postId").isNull()).isTrue();
		assertThat(t.get("preview").asString()).isEqualTo("자전거");
		assertThat(t.get("author").get("id").asLong()).isEqualTo(판매자.getId());
		assertThat(t.get("author").get("nickname").asString()).isEqualTo("mseller");
		assertThat(n.get("reporter").get("id").asLong()).isEqualTo(회원A.getId());
		assertThat(n.get("reason").get("code").asString()).isEqualTo("ABUSE");
		assertThat(n.get("detail").asString()).isEqualTo("욕설");
		assertThat(n.get("status").asString()).isEqualTo("PENDING");
	}

	// 중고 글 신고를 n건 만들고 목록 한 번에 나간 SQL 수를 잰다 (신고 수와 무관해야 N+1이 아니다)
	private long 목록_SQL수(int n, int round) throws Exception {
		jdbc.update("delete from reports");
		jdbc.update("delete from market_items where id <> ?", 글id);
		for (int i = 0; i < n; i++) {
			User 판매 = TestLogin.인증된_사용자(userRepository, jdbc, "ms" + round + "_" + i, "연세대학교");
			User 신고자 = TestLogin.인증된_사용자(userRepository, jdbc, "mr" + round + "_" + i, "연세대학교");
			long id = 중고글_올리기(TestLogin.쿠키(tokenService, 판매), "물건" + i);
			assertThat(신고(TestLogin.쿠키(tokenService, 신고자), id, 본문("SPAM")).getStatus()).isEqualTo(201);
		}
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		stats.clear();
		MockHttpServletResponse res = 관리자목록();
		long count = stats.getPrepareStatementCount();
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).size()).isEqualTo(n);
		return count;
	}

	@Test
	void 조건3_중고_글_신고가_2건이든_10건이든_목록_SQL_실행_수가_같다() throws Exception {
		long small = 목록_SQL수(2, 1);
		long large = 목록_SQL수(10, 2);
		System.out.println("[쿼리수관찰] 중고 2건=" + small + ", 10건=" + large);
		assertThat(large).as("10건 SQL 수").isEqualTo(small);
	}

	// ============ 조건 4: 기각 ============

	@Test
	void 조건4_관리자가_중고_글_신고를_기각하면_200과_DISMISSED이고_글은_남는다() throws Exception {
		long rid = json(신고(쿠키A, 글id, 본문("SPAM"))).get("id").asLong();

		MockHttpServletResponse res = 기각(rid);

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("status").asString()).isEqualTo("DISMISSED");
		assertThat(b.get("target").get("type").asString()).isEqualTo("MARKET_ITEM");
		assertThat(b.get("target").get("preview").asString()).isEqualTo("자전거");
		assertThat(jdbc.queryForObject("select status from reports where id = ?", String.class, rid))
				.isEqualTo("DISMISSED");
		assertThat(jdbc.queryForObject("select count(*) from market_items where id = ?", Long.class, 글id)).isEqualTo(1);
	}

	// ============ 조건 5: CASCADE ============

	@Test
	void 조건5_판매자가_중고_글을_삭제하면_그_신고도_사라지고_다른_글_신고는_남는다() throws Exception {
		long 다른글 = 중고글_올리기(판매자쿠키, "다른물건");
		신고(쿠키A, 글id, 본문("SPAM"));
		신고(쿠키B, 글id, 본문("SPAM"));
		신고(쿠키A, 다른글, 본문("SPAM"));
		assertThat(신고행수()).isEqualTo(3);

		int st = mockMvc.perform(delete("/market-items/" + 글id).with(csrf()).cookie(판매자쿠키)).andReturn().getResponse()
				.getStatus();

		assertThat(st).isEqualTo(204);
		assertThat(신고행수()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select market_item_id from reports", Long.class)).isEqualTo(다른글);
	}

	@Test
	void 조건5_관리자가_신고된_중고_글을_삭제해도_그_신고가_사라진다() throws Exception {
		신고(쿠키A, 글id, 본문("SPAM"));
		assertThat(신고행수()).isEqualTo(1);

		int st = mockMvc.perform(delete("/market-items/" + 글id).with(csrf()).cookie(관리자쿠키)).andReturn().getResponse()
				.getStatus();

		assertThat(st).isEqualTo(204);
		assertThat(신고행수()).isZero();
	}

	// ============ 추가(review 요청): 탈퇴·동시 요청·CSRF ============

	// 실제 가입·로그인으로 만든 회원의 쿠키 (탈퇴는 비밀번호 확인이 필요하다)
	private Cookie 가입하고_로그인(String username) throws Exception {
		String pw = "password123";
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"nickname\":\"" + username + "\",\"password\":\"" + pw + "\"}"))
				.andReturn();
		MockHttpServletResponse login = mockMvc.perform(post("/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"" + username + "\",\"password\":\"" + pw + "\"}"))
				.andReturn().getResponse();
		assertThat(login.getStatus()).isEqualTo(200);
		TestLogin.학교_인증_처리(jdbc, username, "연세대학교");
		return new Cookie("access_token", login.getCookie("access_token").getValue());
	}

	private int 탈퇴(Cookie c) throws Exception {
		return mockMvc.perform(delete("/users/me").with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"password123\"}")).andReturn().getResponse().getStatus();
	}

	@Test
	void 판매자가_탈퇴하면_그_중고_글과_신고가_함께_사라지고_다른_판매자_글_신고는_남는다() throws Exception {
		Cookie 탈퇴판매자 = 가입하고_로그인("leaveseller");
		long 내글 = 중고글_올리기(탈퇴판매자, "탈퇴판매자 물건");
		신고(쿠키A, 내글, 본문("SPAM"));
		신고(쿠키B, 내글, 본문("SPAM"));
		신고(쿠키A, 글id, 본문("SPAM")); // 다른 판매자(mseller)의 글 신고
		assertThat(신고행수()).isEqualTo(3);

		assertThat(탈퇴(탈퇴판매자)).isEqualTo(204);

		assertThat(jdbc.queryForObject("select count(*) from market_items where id = ?", Long.class, 내글)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from reports where market_item_id = ?", Long.class, 내글)).isZero();
		assertThat(신고행수()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select market_item_id from reports", Long.class)).isEqualTo(글id);
	}

	@Test
	void 신고자가_탈퇴하면_중고_글_신고는_남고_reporter_id만_NULL이며_관리자_목록에서_reporter가_null이다() throws Exception {
		Cookie 탈퇴신고자 = 가입하고_로그인("leavereporter");
		assertThat(신고(탈퇴신고자, 글id, 본문("SPAM")).getStatus()).isEqualTo(201);

		assertThat(탈퇴(탈퇴신고자)).isEqualTo(204);

		assertThat(신고행수()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from reports where reporter_id is null", Long.class)).isEqualTo(1);
		MockHttpServletResponse res = 관리자목록();
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = json(res);
		assertThat(arr.size()).isEqualTo(1);
		assertThat(arr.get(0).get("target").get("type").asString()).isEqualTo("MARKET_ITEM");
		assertThat(arr.get(0).get("reporter").isNull()).isTrue();
	}

	// 두 요청이 existsBy 확인을 둘 다 통과해도 DB UNIQUE가 하나만 살린다 (글·댓글 신고 동시 테스트와 같은 방식)
	@Test
	void 같은_회원이_같은_중고_글을_동시에_2번_신고하면_201_하나_409_하나이고_행은_1개다() throws Exception {
		int n = 2;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await(); // 모두 준비되면 한꺼번에 출발
					return 신고(쿠키A, 글id, 본문("SPAM")).getStatus();
				}));
			}
			ready.await();
			go.countDown();

			int created = 0;
			int conflict = 0;
			for (Future<Integer> f : futures) {
				int st = f.get();
				if (st == 201) {
					created++;
				} else if (st == 409) {
					conflict++;
				}
			}
			assertThat(created).as("201 횟수").isEqualTo(1);
			assertThat(conflict).as("409 횟수").isEqualTo(1);
			assertThat(신고행수()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	@Test
	void CSRF_없이_중고_글을_신고하면_403이고_저장되지_않는다() throws Exception {
		int st = mockMvc.perform(post("/market-items/" + 글id + "/reports").cookie(쿠키A)
				.contentType(MediaType.APPLICATION_JSON).content(본문("SPAM"))).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(403);
		assertThat(신고행수()).isZero();
	}

	// ============ 조건 6: DB 제약 ============

	@Test
	void 조건6_대상이_셋_다_NULL인_신고는_ck_reports_target에_막힌다() {
		assertThatThrownBy(() -> 직접삽입(null, null, null, 회원A.getId())).isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_reports_target");
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건6_대상이_둘_이상_채워진_신고는_ck_reports_target에_막힌다() throws Exception {
		School 연세대 = TestLogin.학교(schoolRepository, "연세대학교");
		Post 게시글 = postRepository.save(new Post("게시판 글", "내용", 판매자, 연세대, PostCategory.CHAT));

		// 글 + 중고 글
		assertThatThrownBy(() -> 직접삽입(게시글.getId(), null, 글id, 회원A.getId()))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_target");
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건6_같은_회원이_같은_중고_글에_두_번_INSERT하면_uk_reports_market_item_reporter에_막힌다() {
		직접삽입(null, null, 글id, 회원A.getId());

		assertThatThrownBy(() -> 직접삽입(null, null, 글id, 회원A.getId()))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_reports_market_item_reporter");
		assertThat(신고행수()).isEqualTo(1);
	}

	@Test
	void 조건6_중고_글만_채운_INSERT는_제약을_통과한다() {
		직접삽입(null, null, 글id, 회원A.getId());
		assertThat(신고행수()).isEqualTo(1);
	}
}
