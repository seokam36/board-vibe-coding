package com.example.demo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
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
import com.example.demo.report.domain.Report;
import com.example.demo.report.domain.ReportReason;
import com.example.demo.report.repository.ReportRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.domain.UserRole;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S9-c 관리자 신고 목록·기각 API 완료 조건 검사 (조건 1~7)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AdminReportApiTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PostRepository postRepository;
	@Autowired
	CommentRepository commentRepository;
	@Autowired
	ReportRepository reportRepository;
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
	@Autowired
	EntityManagerFactory emf;

	User 관리자;
	User 글쓴이;
	User 신고자;
	Cookie 관리자쿠키;
	Post 글;
	Comment 댓글;

	@AfterEach
	void 정리() {
		// 글을 먼저 지우면 댓글·신고는 DB가 CASCADE로 지운다 -> 그 다음 회원
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	@BeforeEach
	void 준비() {
		정리();
		관리자 = TestLogin.관리자(userRepository, jdbc, "admin");
		글쓴이 = TestLogin.사용자(userRepository, "writer");
		신고자 = TestLogin.사용자(userRepository, "reporter");
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		글 = postRepository.save(new Post("글 제목", "내용", 글쓴이, null, PostCategory.CHAT));
		댓글 = commentRepository.save(new Comment(글, 글쓴이, "댓글 내용"));
	}

	// ---------- 도우미 ----------

	private Report 글신고저장(Post p, User reporter, ReportReason reason, String detail) {
		return reportRepository.save(Report.ofPost(p, reporter, reason, detail));
	}

	private Report 댓글신고저장(Comment c, User reporter, ReportReason reason, String detail) {
		return reportRepository.save(Report.ofComment(c, reporter, reason, detail));
	}

	private MockHttpServletResponse 목록(Cookie cookie, String query) throws Exception {
		var req = get("/admin/reports" + query);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 기각(Cookie cookie, Object id) throws Exception {
		var req = put("/admin/reports/" + id + "/dismiss").with(csrf());
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private JsonNode json(MockHttpServletResponse res) throws Exception {
		return om.readTree(res.getContentAsString());
	}

	private Cookie 클레임_토큰(User u, Object roleClaim) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder b = JwtClaimsSet.builder().subject(String.valueOf(u.getId()))
				.claim("username", u.getUsername()).issuedAt(now).expiresAt(now.plusSeconds(900));
		if (roleClaim != null) {
			b.claim("role", roleClaim);
		}
		String t = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), b.build()))
				.getTokenValue();
		return new Cookie("access_token", t);
	}

	// role=ADMIN인데 id가 DB에 없는 유령 관리자 토큰
	private Cookie 유령_관리자_토큰() {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("999999999").claim("username", "ghost").claim("role", "ADMIN")
				.issuedAt(now).expiresAt(now.plusSeconds(900)).build();
		String t = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new Cookie("access_token", t);
	}

	private List<Long> 목록_id들(String query) throws Exception {
		MockHttpServletResponse res = 목록(관리자쿠키, query);
		assertThat(res.getStatus()).isEqualTo(200);
		List<Long> ids = new ArrayList<>();
		for (JsonNode n : json(res)) {
			ids.add(n.get("id").asLong());
		}
		return ids;
	}

	// ============ 조건 1: 접근 ============

	@Test
	void 조건1_비로그인은_GET_PUT_모두_401이다() throws Exception {
		assertThat(목록(null, "").getStatus()).isEqualTo(401);
		assertThat(기각(null, 1).getStatus()).isEqualTo(401);
	}

	@Test
	void 조건1_일반_회원은_GET_PUT_모두_403이다() throws Exception {
		Cookie c = TestLogin.쿠키(tokenService, 신고자);
		assertThat(목록(c, "").getStatus()).isEqualTo(403);
		assertThat(기각(c, 1).getStatus()).isEqualTo(403);
	}

	@Test
	void 조건1_role_클레임이_없는_옛_토큰은_GET_PUT_모두_403이다() throws Exception {
		Cookie c = 클레임_토큰(관리자, null);
		assertThat(목록(c, "").getStatus()).isEqualTo(403);
		assertThat(기각(c, 1).getStatus()).isEqualTo(403);
	}

	@Test
	void 조건1_이상한_role_값_토큰은_GET_PUT_모두_403이다() throws Exception {
		Cookie c = 클레임_토큰(관리자, "SUPERUSER");
		assertThat(목록(c, "").getStatus()).isEqualTo(403);
		assertThat(기각(c, 1).getStatus()).isEqualTo(403);
	}

	@Test
	void 조건1_토큰은_ADMIN인데_DB에_회원이_없으면_GET_PUT_모두_401이다() throws Exception {
		Cookie c = 유령_관리자_토큰();
		assertThat(목록(c, "").getStatus()).isEqualTo(401);
		assertThat(기각(c, 1).getStatus()).isEqualTo(401);
	}

	@Test
	void 조건1_토큰은_ADMIN인데_DB가_USER로_강등됐으면_GET_PUT_모두_403이다() throws Exception {
		jdbc.update("update users set role = 'USER' where id = ?", 관리자.getId());
		assertThat(목록(관리자쿠키, "").getStatus()).isEqualTo(403);
		assertThat(기각(관리자쿠키, 1).getStatus()).isEqualTo(403);
	}

	@Test
	void 조건1_PUT은_CSRF가_없으면_관리자여도_403이고_기각되지_않는다() throws Exception {
		Report r = 글신고저장(글, 신고자, ReportReason.SPAM, null);
		int st = mockMvc.perform(put("/admin/reports/" + r.getId() + "/dismiss").cookie(관리자쿠키)).andReturn()
				.getResponse().getStatus();
		assertThat(st).isEqualTo(403);
		assertThat(jdbc.queryForObject("select status from reports where id = ?", String.class, r.getId()))
				.isEqualTo("PENDING");
	}

	// 관찰용: 보안 계층 401/403과 서비스가 던진 401/403의 본문 모양 비교. 판정(assert)은 상태코드만, 본문은 출력한다.
	@Test
	void 조건1_관찰_보안계층과_서비스의_401_403_본문_모양을_출력한다() throws Exception {
		MockHttpServletResponse sec401 = 목록(null, "");
		MockHttpServletResponse svc401 = 목록(유령_관리자_토큰(), "");
		MockHttpServletResponse sec403 = 목록(TestLogin.쿠키(tokenService, 신고자), "");
		jdbc.update("update users set role = 'USER' where id = ?", 관리자.getId());
		MockHttpServletResponse svc403 = 목록(관리자쿠키, "");

		assertThat(sec401.getStatus()).isEqualTo(401);
		assertThat(svc401.getStatus()).isEqualTo(401);
		assertThat(sec403.getStatus()).isEqualTo(403);
		assertThat(svc403.getStatus()).isEqualTo(403);
	}

	// ============ 조건 2: 목록 ============

	@Test
	void 조건2_신고가_없으면_200과_빈_배열이다() throws Exception {
		MockHttpServletResponse res = 목록(관리자쿠키, "");
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).isArray()).isTrue();
		assertThat(json(res).size()).isZero();
	}

	@Test
	void 조건2_기본은_PENDING만_id_오름차순이고_status_DISMISSED는_기각된_것만_준다() throws Exception {
		Report r1 = 글신고저장(글, 신고자, ReportReason.SPAM, null);
		Report r2 = 댓글신고저장(댓글, 신고자, ReportReason.ABUSE, null);
		Report r3 = 글신고저장(글, TestLogin.사용자(userRepository, "reporter2"), ReportReason.OTHER, null);
		jdbc.update("update reports set status = 'DISMISSED' where id = ?", r2.getId());

		assertThat(목록_id들("")).containsExactly(r1.getId(), r3.getId());
		assertThat(목록_id들("?status=PENDING")).containsExactly(r1.getId(), r3.getId());
		assertThat(목록_id들("?status=DISMISSED")).containsExactly(r2.getId());
	}

	@Test
	void 조건2_status가_모르는_값이면_400이다() throws Exception {
		assertThat(목록(관리자쿠키, "?status=XXX").getStatus()).isEqualTo(400);
	}

	@Test
	void 조건2_글_신고_항목의_모든_필드가_맞다() throws Exception {
		Report r = 글신고저장(글, 신고자, ReportReason.SPAM, "광고예요");

		JsonNode arr = json(목록(관리자쿠키, ""));
		assertThat(arr.size()).isEqualTo(1);
		JsonNode n = arr.get(0);
		assertThat(n.get("id").asLong()).isEqualTo(r.getId());
		JsonNode t = n.get("target");
		assertThat(t.get("type").asString()).isEqualTo("POST");
		assertThat(t.get("id").asLong()).isEqualTo(글.getId());
		assertThat(t.get("postId").asLong()).isEqualTo(글.getId());
		assertThat(t.get("preview").asString()).isEqualTo("글 제목");
		assertThat(t.get("author").get("id").asLong()).isEqualTo(글쓴이.getId());
		assertThat(t.get("author").get("nickname").asString()).isEqualTo("writer");
		assertThat(n.get("reporter").get("id").asLong()).isEqualTo(신고자.getId());
		assertThat(n.get("reporter").get("nickname").asString()).isEqualTo("reporter");
		assertThat(n.get("reason").get("code").asString()).isEqualTo("SPAM");
		assertThat(n.get("reason").get("name").asString()).isEqualTo("광고·스팸");
		assertThat(n.get("detail").asString()).isEqualTo("광고예요");
		assertThat(n.get("status").asString()).isEqualTo("PENDING");
		assertThat(Instant.parse(n.get("createdAt").asString())).isNotNull();
	}

	@Test
	void 조건2_댓글_신고_항목의_모든_필드가_맞다() throws Exception {
		Report r = 댓글신고저장(댓글, 신고자, ReportReason.PRIVACY, null);

		JsonNode arr = json(목록(관리자쿠키, ""));
		assertThat(arr.size()).isEqualTo(1);
		JsonNode n = arr.get(0);
		assertThat(n.get("id").asLong()).isEqualTo(r.getId());
		JsonNode t = n.get("target");
		assertThat(t.get("type").asString()).isEqualTo("COMMENT");
		assertThat(t.get("id").asLong()).isEqualTo(댓글.getId());
		assertThat(t.get("postId").asLong()).isEqualTo(글.getId());
		assertThat(t.get("preview").asString()).isEqualTo("댓글 내용");
		assertThat(t.get("author").get("id").asLong()).isEqualTo(글쓴이.getId());
		assertThat(t.get("author").get("nickname").asString()).isEqualTo("writer");
		assertThat(n.get("reporter").get("id").asLong()).isEqualTo(신고자.getId());
		assertThat(n.get("reason").get("code").asString()).isEqualTo("PRIVACY");
		assertThat(n.get("reason").get("name").asString()).isNotBlank();
		assertThat(n.get("detail").isNull()).isTrue();
		assertThat(n.get("status").asString()).isEqualTo("PENDING");
		assertThat(Instant.parse(n.get("createdAt").asString())).isNotNull();
	}

	// ============ 조건 3: null 처리 ============

	@Test
	void 조건3_신고자가_탈퇴하면_reporter가_null이고_신고는_남는다() throws Exception {
		글신고저장(글, 신고자, ReportReason.SPAM, null);
		댓글신고저장(댓글, 신고자, ReportReason.SPAM, null);
		jdbc.update("delete from users where id = ?", 신고자.getId());

		JsonNode arr = json(목록(관리자쿠키, ""));
		assertThat(arr.size()).isEqualTo(2);
		for (JsonNode n : arr) {
			assertThat(n.get("reporter").isNull()).isTrue();
			assertThat(n.get("target").get("author").get("id").asLong()).isEqualTo(글쓴이.getId());
		}
	}

	@Test
	void 조건3_대상_작성자가_탈퇴하면_target_author가_null이다() throws Exception {
		글신고저장(글, 신고자, ReportReason.SPAM, null);
		댓글신고저장(댓글, 신고자, ReportReason.SPAM, null);
		// 실제 탈퇴와 같은 순서: 글·댓글의 작성자를 먼저 비우고(FK에 SET NULL 없음) 회원을 지운다
		jdbc.update("update posts set author_id = null where author_id = ?", 글쓴이.getId());
		jdbc.update("update comments set author_id = null where author_id = ?", 글쓴이.getId());
		jdbc.update("delete from users where id = ?", 글쓴이.getId());

		JsonNode arr = json(목록(관리자쿠키, ""));
		assertThat(arr.size()).isEqualTo(2);
		for (JsonNode n : arr) {
			assertThat(n.get("target").get("author").isNull()).isTrue();
			assertThat(n.get("reporter").get("id").asLong()).isEqualTo(신고자.getId());
		}
	}

	@Test
	void 조건3_작성자_없는_옛_글과_댓글의_target_author는_null이다() throws Exception {
		Post 옛글 = postRepository.save(new Post("옛 글", "내용", null, null, PostCategory.CHAT));
		Comment 옛댓글 = commentRepository.save(new Comment(옛글, null, "옛 댓글"));
		글신고저장(옛글, 신고자, ReportReason.SPAM, null);
		댓글신고저장(옛댓글, 신고자, ReportReason.SPAM, null);

		JsonNode arr = json(목록(관리자쿠키, ""));
		assertThat(arr.size()).isEqualTo(2);
		for (JsonNode n : arr) {
			assertThat(n.get("target").get("author").isNull()).isTrue();
		}
		assertThat(arr.get(1).get("target").get("preview").asString()).isEqualTo("옛 댓글");
	}

	// ============ 조건 4: preview ============

	private String 댓글_preview(String content) throws Exception {
		Comment c = commentRepository.save(new Comment(글, 글쓴이, content));
		댓글신고저장(c, 신고자, ReportReason.SPAM, null);
		JsonNode arr = json(목록(관리자쿠키, ""));
		return arr.get(arr.size() - 1).get("target").get("preview").asString();
	}

	@Test
	void 조건4_글_신고의_preview는_제목_그대로다() throws Exception {
		글신고저장(글, 신고자, ReportReason.SPAM, null);
		assertThat(json(목록(관리자쿠키, "")).get(0).get("target").get("preview").asString()).isEqualTo("글 제목");
	}

	@Test
	void 조건4_댓글_정확히_100글자는_그대로다() throws Exception {
		String content = "가".repeat(100);
		assertThat(댓글_preview(content)).isEqualTo(content);
	}

	@Test
	void 조건4_댓글_101글자는_100글자와_말줄임표다() throws Exception {
		String preview = 댓글_preview("가".repeat(101));
		assertThat(preview).isEqualTo("가".repeat(100) + "…");
		assertThat(preview.length()).isEqualTo(101);
	}

	@Test
	void 조건4_자르는_경계에_이모지가_있어도_깨지지_않는다() throws Exception {
		// 99글자 + 이모지(코드포인트 1개, UTF-16 2칸) + x = 101 코드포인트 -> 앞 100 코드포인트 = 99글자 + 이모지
		String preview = 댓글_preview("가".repeat(99) + "😀" + "x");
		assertThat(preview).isEqualTo("가".repeat(99) + "😀" + "…");
		assertThat(preview.codePointCount(0, preview.length())).isEqualTo(101);
	}

	@Test
	void 조건4_이모지로_끝나는_정확히_100코드포인트는_그대로다() throws Exception {
		String content = "가".repeat(99) + "😀";
		assertThat(댓글_preview(content)).isEqualTo(content);
	}

	// ============ 조건 5: N+1 ============

	// 글·댓글 신고를 섞어 n건 만들고(작성자·신고자 모두 다름), GET 한 번에 나간 SQL 준비 횟수를 잰다
	private long 목록_SQL수(int n, int round) throws Exception {
		postRepository.deleteAll();
		for (int i = 0; i < n; i++) {
			User author = TestLogin.사용자(userRepository, "au" + round + "_" + i);
			User rep = TestLogin.사용자(userRepository, "rp" + round + "_" + i);
			Post p = postRepository.save(new Post("제목" + i, "내용", author, null, PostCategory.CHAT));
			if (i % 2 == 0) {
				글신고저장(p, rep, ReportReason.SPAM, null);
			} else {
				Comment c = commentRepository.save(new Comment(p, author, "댓글" + i));
				댓글신고저장(c, rep, ReportReason.ABUSE, null);
			}
		}
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		stats.clear();
		MockHttpServletResponse res = 목록(관리자쿠키, "");
		long count = stats.getPrepareStatementCount();
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).size()).isEqualTo(n);
		return count;
	}

	@Test
	void 조건5_신고_수가_2건이든_10건이든_SQL_실행_수가_같다() throws Exception {
		long small = 목록_SQL수(2, 1);
		long large = 목록_SQL수(10, 2);
		System.out.println("[쿼리수관찰] 2건=" + small + ", 10건=" + large);
		assertThat(large).as("10건 SQL 수").isEqualTo(small);
	}

	// ============ 조건 6: 기각 ============

	@Test
	void 조건6_기각하면_200과_DISMISSED이고_목록이_PENDING에서_DISMISSED로_옮겨진다() throws Exception {
		Report r = 글신고저장(글, 신고자, ReportReason.SPAM, "광고예요");

		MockHttpServletResponse res = 기각(관리자쿠키, r.getId());

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("id").asLong()).isEqualTo(r.getId());
		assertThat(b.get("status").asString()).isEqualTo("DISMISSED");
		assertThat(b.get("target").get("type").asString()).isEqualTo("POST");
		assertThat(b.get("target").get("preview").asString()).isEqualTo("글 제목");
		assertThat(b.get("reporter").get("id").asLong()).isEqualTo(신고자.getId());
		assertThat(b.get("detail").asString()).isEqualTo("광고예요");
		assertThat(목록_id들("")).isEmpty();
		assertThat(목록_id들("?status=DISMISSED")).containsExactly(r.getId());
		// 신고된 글은 그대로 남는다
		assertThat(postRepository.existsById(글.getId())).isTrue();
	}

	@Test
	void 조건6_없는_신고_id를_기각하면_404다() throws Exception {
		assertThat(기각(관리자쿠키, 999999999L).getStatus()).isEqualTo(404);
	}

	@Test
	void 조건6_이미_기각된_신고를_다시_기각하면_409다() throws Exception {
		Report r = 글신고저장(글, 신고자, ReportReason.SPAM, null);
		assertThat(기각(관리자쿠키, r.getId()).getStatus()).isEqualTo(200);
		assertThat(기각(관리자쿠키, r.getId()).getStatus()).isEqualTo(409);
	}

	@Test
	void 조건6_id가_숫자가_아니면_400이다() throws Exception {
		assertThat(기각(관리자쿠키, "abc").getStatus()).isEqualTo(400);
	}

	@Test
	void 조건6_동시에_두_번_기각하면_200_하나_409_하나다() throws Exception {
		Report r = 글신고저장(글, 신고자, ReportReason.SPAM, null);
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
					return 기각(관리자쿠키, r.getId()).getStatus();
				}));
			}
			ready.await();
			go.countDown();
			int ok = 0;
			int conflict = 0;
			for (Future<Integer> f : futures) {
				int st = f.get();
				if (st == 200) {
					ok++;
				} else if (st == 409) {
					conflict++;
				}
			}
			assertThat(ok).as("200 횟수").isEqualTo(1);
			assertThat(conflict).as("409 횟수").isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	// 신고 행이 잠긴 채 기각 UPDATE가 대기하다가, 대상 글 삭제(신고 CASCADE 삭제)가 커밋되면 0행 -> 404
	@Test
	void 조건6_기각_직전에_대상_글_삭제가_커밋되면_404다() throws Exception {
		Report r = 글신고저장(글, 신고자, ReportReason.SPAM, null);
		Long rid = r.getId();
		Long pid = 글.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from reports where id = ? for update", Long.class, rid),
				() -> 기각(관리자쿠키, rid).getStatus(),
				() -> jdbc.update("delete from posts where id = ?", pid));

		assertThat(status).isEqualTo(404);
		assertThat(jdbc.queryForObject("select count(*) from reports", Long.class)).isZero();
	}

	// ============ 조건 7: 관리자 삭제 후 목록 ============

	@Test
	void 조건7_관리자가_신고된_글을_삭제하면_그_글과_그_글_댓글의_신고가_목록에서_사라진다() throws Exception {
		Post 다른글 = postRepository.save(new Post("다른 글", "내용", 글쓴이, null, PostCategory.CHAT));
		글신고저장(글, 신고자, ReportReason.SPAM, null);
		댓글신고저장(댓글, 신고자, ReportReason.SPAM, null);
		Report 남는신고 = 글신고저장(다른글, 신고자, ReportReason.SPAM, null);
		assertThat(목록_id들("")).hasSize(3);

		int st = mockMvc.perform(delete("/posts/" + 글.getId()).with(csrf()).cookie(관리자쿠키)).andReturn().getResponse()
				.getStatus();

		assertThat(st).isEqualTo(204);
		assertThat(목록_id들("")).containsExactly(남는신고.getId());
	}

	// 조건 4 보조: UserRole import 사용(관리자 role이 실제 ADMIN인지 사전 확인)
	@Test
	void 준비된_관리자는_DB에서_ADMIN이다() {
		assertThat(userRepository.findById(관리자.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ADMIN);
	}
}
