package com.example.demo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.comment.domain.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S9-b 글·댓글 신고 API 완료 조건 검사 (완료 조건 2~9)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ReportApiTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PostRepository postRepository;
	@Autowired
	CommentRepository commentRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	TokenService tokenService;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ObjectMapper om;
	@Autowired
	JwtEncoder jwtEncoder;
	@Autowired
	PlatformTransactionManager txManager;

	User 글쓴이;
	User 회원A;
	User 회원B;
	Cookie 글쓴이쿠키;
	Cookie 쿠키A;
	Cookie 쿠키B;
	Post 글;
	Comment 댓글; // 글쓴이가 쓴 댓글

	// 글을 먼저 지우면 댓글·신고도 DB가 CASCADE로 지운다 -> 그 다음 회원을 지운다
	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	@BeforeEach
	void 준비() {
		정리();
		글쓴이 = TestLogin.사용자(userRepository, "writer");
		회원A = TestLogin.사용자(userRepository, "memberA");
		회원B = TestLogin.사용자(userRepository, "memberB");
		글쓴이쿠키 = TestLogin.쿠키(tokenService, 글쓴이);
		쿠키A = TestLogin.쿠키(tokenService, 회원A);
		쿠키B = TestLogin.쿠키(tokenService, 회원B);
		글 = postRepository.save(new Post("제목", "내용", 글쓴이, null, PostCategory.CHAT));
		댓글 = commentRepository.save(new Comment(글, 글쓴이, "댓글 내용"));
	}

	// ---------- 도우미 ----------

	private static String 본문(String reason) {
		return "{\"reason\":\"" + reason + "\"}";
	}

	private MockHttpServletResponse 글신고(Cookie cookie, Object postId, String body) throws Exception {
		var req = post("/posts/" + postId + "/reports").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 댓글신고(Cookie cookie, Object commentId, String body) throws Exception {
		var req = post("/comments/" + commentId + "/reports").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(body);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private JsonNode json(MockHttpServletResponse res) throws Exception {
		return om.readTree(res.getContentAsString());
	}

	private long 신고행수() {
		return jdbc.queryForObject("select count(*) from reports", Long.class);
	}

	private MockHttpServletResponse 관리자삭제(String path) throws Exception {
		User admin = TestLogin.관리자(userRepository, jdbc, "admin");
		return mockMvc.perform(delete(path).with(csrf()).cookie(TestLogin.관리자_쿠키(tokenService, admin))).andReturn()
				.getResponse();
	}

	// ============ 조건 2: 사유 목록 ============

	@Test
	void 조건2_비로그인으로_사유_목록을_조회하면_200이고_5개_code와_name을_반환한다() throws Exception {
		MockHttpServletResponse res = mockMvc.perform(get("/report-reasons")).andReturn().getResponse();

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = json(res);
		assertThat(arr.size()).isEqualTo(5);
		List<String> codes = new ArrayList<>();
		for (JsonNode n : arr) {
			codes.add(n.get("code").asString());
			assertThat(n.get("name").asString()).isNotBlank();
		}
		assertThat(codes).containsExactly("SPAM", "ABUSE", "SEXUAL", "PRIVACY", "OTHER");
		assertThat(arr.get(0).get("name").asString()).isEqualTo("광고·스팸");
	}

	@Test
	void 조건2_깨진_토큰_쿠키가_붙어도_사유_목록은_200이다() throws Exception {
		int st = mockMvc.perform(get("/report-reasons").cookie(new Cookie("access_token", "garbage"))).andReturn()
				.getResponse().getStatus();
		assertThat(st).isEqualTo(200);
	}

	// ============ 조건 3: 성공 ============

	@Test
	void 조건3_남의_글을_신고하면_201과_응답_모양이고_DB에_저장된다() throws Exception {
		MockHttpServletResponse res = 글신고(쿠키A, 글.getId(), "{\"reason\":\"SPAM\",\"detail\":\"광고예요\"}");

		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode b = json(res);
		assertThat(b.get("id").asLong()).isPositive();
		assertThat(b.get("target").get("type").asString()).isEqualTo("POST");
		assertThat(b.get("target").get("id").asLong()).isEqualTo(글.getId());
		assertThat(b.get("target").get("postId").asLong()).isEqualTo(글.getId());
		assertThat(b.get("reason").get("code").asString()).isEqualTo("SPAM");
		assertThat(b.get("reason").get("name").asString()).isEqualTo("광고·스팸");
		assertThat(b.get("detail").asString()).isEqualTo("광고예요");
		assertThat(b.get("status").asString()).isEqualTo("PENDING");
		assertThat(b.get("createdAt").asString()).isNotBlank();
		assertThat(res.getHeader("Location")).isNull();

		// DB 행 확인: 신고자는 토큰의 회원, 대상은 글(댓글 칸은 비어 있음)
		var row = jdbc.queryForMap("select * from reports");
		assertThat(((Number) row.get("id")).longValue()).isEqualTo(b.get("id").asLong());
		assertThat(((Number) row.get("post_id")).longValue()).isEqualTo(글.getId());
		assertThat(row.get("comment_id")).isNull();
		assertThat(((Number) row.get("reporter_id")).longValue()).isEqualTo(회원A.getId());
		assertThat(row.get("reason")).isEqualTo("SPAM");
		assertThat(row.get("detail")).isEqualTo("광고예요");
		assertThat(row.get("status")).isEqualTo("PENDING");
	}

	@Test
	void 조건3_detail_없이_신고해도_201이고_detail은_null이다() throws Exception {
		MockHttpServletResponse res = 글신고(쿠키A, 글.getId(), 본문("ABUSE"));

		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(json(res).get("detail").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from reports where detail is null", Long.class)).isEqualTo(1);
	}

	@Test
	void 조건3_공백뿐인_detail과_빈_detail은_null로_저장된다() throws Exception {
		MockHttpServletResponse r1 = 글신고(쿠키A, 글.getId(), "{\"reason\":\"OTHER\",\"detail\":\"   \"}");
		MockHttpServletResponse r2 = 글신고(쿠키B, 글.getId(), "{\"reason\":\"OTHER\",\"detail\":\"\"}");

		assertThat(r1.getStatus()).isEqualTo(201);
		assertThat(json(r1).get("detail").isNull()).isTrue();
		assertThat(r2.getStatus()).isEqualTo(201);
		assertThat(json(r2).get("detail").isNull()).isTrue();
		assertThat(jdbc.queryForObject("select count(*) from reports where detail is null", Long.class)).isEqualTo(2);
	}

	@Test
	void 조건3_내용이_있는_detail은_앞뒤_공백까지_원문_그대로_저장된다() throws Exception {
		MockHttpServletResponse res = 글신고(쿠키A, 글.getId(), "{\"reason\":\"OTHER\",\"detail\":\" 광고 \"}");

		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(json(res).get("detail").asString()).isEqualTo(" 광고 ");
		assertThat(jdbc.queryForObject("select detail from reports", String.class)).isEqualTo(" 광고 ");
	}

	@Test
	void 조건3_남의_댓글을_신고하면_201이고_target은_COMMENT와_그_댓글의_글_id다() throws Exception {
		MockHttpServletResponse res = 댓글신고(쿠키A, 댓글.getId(), "{\"reason\":\"PRIVACY\",\"detail\":\"번호 노출\"}");

		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode b = json(res);
		assertThat(b.get("target").get("type").asString()).isEqualTo("COMMENT");
		assertThat(b.get("target").get("id").asLong()).isEqualTo(댓글.getId());
		assertThat(b.get("target").get("postId").asLong()).isEqualTo(글.getId());
		assertThat(b.get("reason").get("code").asString()).isEqualTo("PRIVACY");
		assertThat(b.get("status").asString()).isEqualTo("PENDING");

		var row = jdbc.queryForMap("select * from reports");
		assertThat(row.get("post_id")).isNull();
		assertThat(((Number) row.get("comment_id")).longValue()).isEqualTo(댓글.getId());
		assertThat(((Number) row.get("reporter_id")).longValue()).isEqualTo(회원A.getId());
	}

	// ============ 조건 4: 400 ============

	@Test
	void 조건4_reason이_없으면_400이고_저장되지_않는다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), "{}").getStatus()).isEqualTo(400);
		assertThat(댓글신고(쿠키A, 댓글.getId(), "{\"detail\":\"x\"}").getStatus()).isEqualTo(400);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건4_사유목록에_없는_reason_XXX면_400이다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), 본문("XXX")).getStatus()).isEqualTo(400);
		assertThat(댓글신고(쿠키A, 댓글.getId(), 본문("XXX")).getStatus()).isEqualTo(400);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건4_detail_301자는_400이고_300자는_201이다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), "{\"reason\":\"OTHER\",\"detail\":\"" + "가".repeat(301) + "\"}").getStatus())
				.isEqualTo(400);
		assertThat(신고행수()).isZero();
		assertThat(글신고(쿠키A, 글.getId(), "{\"reason\":\"OTHER\",\"detail\":\"" + "가".repeat(300) + "\"}").getStatus())
				.isEqualTo(201);
		assertThat(신고행수()).isEqualTo(1);
	}

	@Test
	void 조건4_detail에_NUL이_있으면_400이다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), "{\"reason\":\"OTHER\",\"detail\":\"a\\u0000b\"}").getStatus()).isEqualTo(400);
		assertThat(댓글신고(쿠키A, 댓글.getId(), "{\"reason\":\"OTHER\",\"detail\":\"a\\u0000b\"}").getStatus())
				.isEqualTo(400);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건4_자기_글을_신고하면_400이다() throws Exception {
		assertThat(글신고(글쓴이쿠키, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(400);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건4_자기_댓글을_신고하면_400이다() throws Exception {
		assertThat(댓글신고(글쓴이쿠키, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(400);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건4_깨진_JSON이면_400이다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), "{\"reason\":").getStatus()).isEqualTo(400);
		assertThat(댓글신고(쿠키A, 댓글.getId(), "not json").getStatus()).isEqualTo(400);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건4_id가_숫자가_아니면_400이다() throws Exception {
		assertThat(글신고(쿠키A, "abc", 본문("SPAM")).getStatus()).isEqualTo(400);
		assertThat(댓글신고(쿠키A, "abc", 본문("SPAM")).getStatus()).isEqualTo(400);
	}

	// ============ 조건 5: 401 / 403 / 404 / 409 ============

	@Test
	void 조건5_비로그인으로_신고하면_401이다() throws Exception {
		assertThat(글신고(null, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(401);
		assertThat(댓글신고(null, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(401);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건5_유령_토큰이면_401이고_저장되지_않는다() throws Exception {
		Cookie ghost = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(글신고(ghost, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(401);
		assertThat(댓글신고(ghost, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(401);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건5_CSRF_없이_신고하면_403이다() throws Exception {
		int s1 = mockMvc.perform(post("/posts/" + 글.getId() + "/reports").cookie(쿠키A)
				.contentType(MediaType.APPLICATION_JSON).content(본문("SPAM"))).andReturn().getResponse().getStatus();
		int s2 = mockMvc.perform(post("/comments/" + 댓글.getId() + "/reports").cookie(쿠키A)
				.contentType(MediaType.APPLICATION_JSON).content(본문("SPAM"))).andReturn().getResponse().getStatus();
		assertThat(s1).isEqualTo(403);
		assertThat(s2).isEqualTo(403);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건5_없는_글과_없는_댓글을_신고하면_404다() throws Exception {
		assertThat(글신고(쿠키A, 999999999L, 본문("SPAM")).getStatus()).isEqualTo(404);
		assertThat(댓글신고(쿠키A, 999999999L, 본문("SPAM")).getStatus()).isEqualTo(404);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건5_같은_회원이_같은_글을_두_번_신고하면_두번째는_409이고_행은_1개다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(글신고(쿠키A, 글.getId(), 본문("ABUSE")).getStatus()).isEqualTo(409);
		assertThat(신고행수()).isEqualTo(1);
	}

	@Test
	void 조건5_같은_회원이_같은_댓글을_두_번_신고하면_두번째는_409이고_행은_1개다() throws Exception {
		assertThat(댓글신고(쿠키A, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(댓글신고(쿠키A, 댓글.getId(), 본문("ABUSE")).getStatus()).isEqualTo(409);
		assertThat(신고행수()).isEqualTo(1);
	}

	@Test
	void 조건5_다른_회원이_같은_글과_댓글을_신고하면_각각_201이다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(글신고(쿠키B, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(댓글신고(쿠키A, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(댓글신고(쿠키B, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(신고행수()).isEqualTo(4);
	}

	@Test
	void 조건5_같은_회원이_글_신고와_그_글의_댓글_신고는_둘_다_201이다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(댓글신고(쿠키A, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(신고행수()).isEqualTo(2);
	}

	// ---- 검사 순서 (docs/api.md: CSRF 403 -> 로그인 401 -> 본문 400 -> 회원 401 -> 404 -> 자기 것 400 -> 409) ----

	@Test
	void 검사순서_CSRF가_없으면_비로그인이어도_403이다() throws Exception {
		int st = mockMvc.perform(post("/posts/" + 글.getId() + "/reports").contentType(MediaType.APPLICATION_JSON)
				.content(본문("SPAM"))).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(403);
	}

	@Test
	void 검사순서_유령_토큰이어도_본문이_틀리면_400이_먼저다() throws Exception {
		assertThat(글신고(TestLogin.행없는_회원_토큰(jwtEncoder), 글.getId(), 본문("XXX")).getStatus()).isEqualTo(400);
	}

	@Test
	void 검사순서_유령_토큰이면_없는_글이어도_401이_먼저다() throws Exception {
		assertThat(글신고(TestLogin.행없는_회원_토큰(jwtEncoder), 999999999L, 본문("SPAM")).getStatus()).isEqualTo(401);
	}

	// ============ 조건 6: 동시 요청 ============

	@Test
	void 조건6_같은_회원이_같은_글을_동시에_2번_신고하면_201_하나_409_하나이고_행은_1개다() throws Exception {
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
					return 글신고(쿠키A, 글.getId(), 본문("SPAM")).getStatus();
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

	// 댓글 신고도 같은 규칙: 동시에 두 번 보내면 UNIQUE(uk_reports_comment_reporter)가 하나만 통과시킨다
	@Test
	void 조건6_같은_회원이_같은_댓글을_동시에_2번_신고하면_201_하나_409_하나이고_행은_1개다() throws Exception {
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
					return 댓글신고(쿠키A, 댓글.getId(), 본문("SPAM")).getStatus();
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

	// ============ 조건 7: 경쟁 (FK 위반을 제약 이름으로 구분) ============

	// 글 삭제가 커밋 전일 때 신고 INSERT가 대기하다가 글이 사라지면 fk_reports_post 위반 -> 404
	@Test
	void 조건7_신고_저장_직전에_글_삭제가_커밋되면_404이고_신고_행이_안_생긴다() throws Exception {
		Long pid = 글.getId();
		// 이 글에 달린 댓글은 글 삭제 때 같이 지워진다
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from posts where id = ?", pid),
				() -> 글신고(쿠키A, pid, 본문("SPAM")).getStatus(),
				() -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(신고행수()).isZero();
	}

	// 회원 행이 잠긴 채 신고 INSERT가 대기하다가 회원이 삭제 커밋되면 fk_reports_reporter 위반 -> 401
	@Test
	void 조건7_신고_저장_직전에_탈퇴가_커밋되면_401이고_신고_행이_안_생긴다() throws Exception {
		Long uid = 회원A.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from users where id = ? for update", Long.class, uid),
				() -> 글신고(쿠키A, 글.getId(), 본문("SPAM")).getStatus(),
				() -> jdbc.update("delete from users where id = ?", uid));

		assertThat(status).isEqualTo(401);
		assertThat(신고행수()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Long.class, uid)).isZero();
	}

	// 덤: 댓글 신고도 같은 경쟁에서 fk_reports_comment 위반 -> 404
	@Test
	void 조건7_신고_저장_직전에_댓글_삭제가_커밋되면_404이고_신고_행이_안_생긴다() throws Exception {
		Long cid = 댓글.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from comments where id = ?", cid),
				() -> 댓글신고(쿠키A, cid, 본문("SPAM")).getStatus(),
				() -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(신고행수()).isZero();
	}

	// ============ 조건 8: CASCADE / SET NULL ============

	@Test
	void 조건8_신고된_글을_삭제하면_그_글_신고와_그_글_댓글의_신고가_모두_삭제된다() throws Exception {
		// 다른 글의 신고는 남아야 한다
		Post 다른글 = postRepository.save(new Post("다른 글", "내용", 글쓴이, null, PostCategory.CHAT));
		assertThat(글신고(쿠키A, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(댓글신고(쿠키A, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(글신고(쿠키A, 다른글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(신고행수()).isEqualTo(3);

		int st = mockMvc.perform(delete("/posts/" + 글.getId()).with(csrf()).cookie(글쓴이쿠키)).andReturn().getResponse()
				.getStatus();

		assertThat(st).isEqualTo(204);
		assertThat(신고행수()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select post_id from reports", Long.class)).isEqualTo(다른글.getId());
	}

	@Test
	void 조건8_신고된_댓글을_삭제하면_그_댓글_신고가_삭제되고_글_신고는_남는다() throws Exception {
		assertThat(글신고(쿠키A, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(댓글신고(쿠키A, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);

		int st = mockMvc.perform(delete("/comments/" + 댓글.getId()).with(csrf()).cookie(글쓴이쿠키)).andReturn()
				.getResponse().getStatus();

		assertThat(st).isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from reports where comment_id is not null", Long.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from reports where post_id is not null", Long.class)).isEqualTo(1);
	}

	@Test
	void 조건8_관리자가_신고된_글을_삭제해도_글과_댓글의_신고가_모두_삭제된다() throws Exception {
		글신고(쿠키A, 글.getId(), 본문("SPAM"));
		댓글신고(쿠키A, 댓글.getId(), 본문("SPAM"));
		assertThat(신고행수()).isEqualTo(2);

		assertThat(관리자삭제("/posts/" + 글.getId()).getStatus()).isEqualTo(204);

		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건8_관리자가_신고된_댓글을_삭제해도_그_댓글_신고가_삭제된다() throws Exception {
		댓글신고(쿠키A, 댓글.getId(), 본문("SPAM"));
		assertThat(신고행수()).isEqualTo(1);

		assertThat(관리자삭제("/comments/" + 댓글.getId()).getStatus()).isEqualTo(204);

		assertThat(신고행수()).isZero();
	}

	@Test
	void 조건8_신고자가_탈퇴하면_신고_행은_남고_reporter_id만_NULL이다() throws Exception {
		// 탈퇴는 비밀번호 확인이 필요하므로 실제 가입·로그인을 거친 회원을 쓴다
		String pw = "password123";
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"leaver\",\"nickname\":\"탈퇴자\",\"password\":\"" + pw + "\"}")).andReturn();
		MockHttpServletResponse login = mockMvc.perform(post("/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"leaver\",\"password\":\"" + pw + "\"}"))
				.andReturn().getResponse();
		assertThat(login.getStatus()).isEqualTo(200);
		Cookie leaverCookie = new Cookie("access_token", login.getCookie("access_token").getValue());

		assertThat(글신고(leaverCookie, 글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(댓글신고(leaverCookie, 댓글.getId(), 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(jdbc.queryForObject("select count(*) from reports where reporter_id is not null", Long.class))
				.isEqualTo(2);

		int withdraw = mockMvc.perform(delete("/users/me").with(csrf()).cookie(leaverCookie)
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + pw + "\"}"))
				.andReturn().getResponse().getStatus();
		assertThat(withdraw).isEqualTo(204);

		assertThat(신고행수()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from reports where reporter_id is null", Long.class)).isEqualTo(2);
	}

	// ============ 조건 9: 작성자 없는 옛 글·댓글 ============

	@Test
	void 조건9_작성자_없는_옛_글과_댓글도_신고할_수_있다() throws Exception {
		Post 옛글 = postRepository.save(new Post("옛 글", "내용", null, null, PostCategory.CHAT));
		Comment 옛댓글 = commentRepository.save(new Comment(옛글, null, "옛 댓글"));

		MockHttpServletResponse r1 = 글신고(쿠키A, 옛글.getId(), 본문("SPAM"));
		MockHttpServletResponse r2 = 댓글신고(쿠키A, 옛댓글.getId(), 본문("SPAM"));

		assertThat(r1.getStatus()).isEqualTo(201);
		assertThat(r2.getStatus()).isEqualTo(201);
		assertThat(json(r2).get("target").get("postId").asLong()).isEqualTo(옛글.getId());
		assertThat(신고행수()).isEqualTo(2);
	}
}
