package com.example.demo.report;

import static com.example.demo.report.ConversationReportFixture.본문;
import static com.example.demo.report.ConversationReportFixture.신고;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.comment.domain.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.message.repository.ConversationRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.report.domain.Report;
import com.example.demo.report.domain.ReportReason;
import com.example.demo.report.repository.ReportRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S14-f: 관리자 신고 목록(GET /admin/reports)과 기각(PUT /admin/reports/{id}/dismiss)이 대화 신고를 다루는지 검사.
// 특히 "글도 중고도 아니면 댓글"이라 가정하던 곳이 대화 신고 한 건 때문에 500이 나던 문제(planner가 찾은 위험)를 지킨다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AdminConversationReportTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired CommentRepository commentRepository;
	@Autowired MarketItemRepository marketItemRepository;
	@Autowired ConversationRepository conversationRepository;
	@Autowired ReportRepository reportRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired EntityManagerFactory emf;

	User 관리자, 판매자, 구매자;
	Cookie 관리자쿠키, 판매자쿠키, 구매자쿠키;
	long 글id, 방id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		관리자 = TestLogin.관리자(userRepository, jdbc, "acadmin");
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "acseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "acbuyer", "연세대학교");
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		long[] ids = ConversationReportFixture.글과_방_만들기(mockMvc, om, 판매자쿠키, 구매자쿠키, "관리자가 볼 제목");
		글id = ids[0];
		방id = ids[1];
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
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

	private long 대화_신고(Cookie 쿠키) throws Exception {
		MockHttpServletResponse res = 신고(mockMvc, 쿠키, 방id, 본문("ABUSE"));
		assertThat(res.getStatus()).as("준비용 대화 신고").isEqualTo(201);
		return json(res).get("id").asLong();
	}

	private void 탈퇴(Cookie 쿠키) throws Exception {
		int st = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(204);
	}

	// ============ 목록 ============

	@Test
	void 구매자가_신고한_대화는_목록에_CONVERSATION으로_나오고_author는_판매자_preview는_글_제목이다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);

		MockHttpServletResponse res = 목록(관리자쿠키, "");

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = json(res);
		assertThat(arr.size()).isEqualTo(1);
		JsonNode r = arr.get(0);
		assertThat(r.get("id").asLong()).isEqualTo(reportId);
		assertThat(r.get("target").get("type").asString()).isEqualTo("CONVERSATION");
		assertThat(r.get("target").get("id").asLong()).isEqualTo(방id);
		assertThat(r.get("target").get("postId").isNull()).isTrue();
		assertThat(r.get("target").get("preview").asString()).isEqualTo("관리자가 볼 제목");
		assertThat(r.get("target").get("author").get("id").asLong()).as("신고자가 아닌 참가자 = 판매자").isEqualTo(판매자.getId());
		assertThat(r.get("target").get("author").get("nickname").asString()).isEqualTo("acseller");
		assertThat(r.get("reporter").get("id").asLong()).isEqualTo(구매자.getId());
		assertThat(r.get("reason").get("code").asString()).isEqualTo("ABUSE");
		assertThat(r.get("status").asString()).isEqualTo("PENDING");
	}

	@Test
	void 판매자가_신고하면_author는_구매자이다() throws Exception {
		대화_신고(판매자쿠키);

		JsonNode r = json(목록(관리자쿠키, "")).get(0);

		assertThat(r.get("target").get("author").get("id").asLong()).isEqualTo(구매자.getId());
		assertThat(r.get("reporter").get("id").asLong()).isEqualTo(판매자.getId());
	}

	@Test
	void 상대가_탈퇴한_방이면_author가_null이다() throws Exception {
		대화_신고(구매자쿠키);
		탈퇴(판매자쿠키); // 판매자 탈퇴 -> 방의 판매자 칸 NULL, 글도 삭제됨

		MockHttpServletResponse res = 목록(관리자쿠키, "");

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode r = json(res).get(0);
		assertThat(r.get("target").get("type").asString()).isEqualTo("CONVERSATION");
		assertThat(r.get("target").get("author").isNull()).isTrue();
		assertThat(r.get("target").get("preview").asString()).as("제목은 방에 복사돼 있다").isEqualTo("관리자가 볼 제목");
		assertThat(r.get("reporter").get("id").asLong()).isEqualTo(구매자.getId());
	}

	@Test
	void 신고자가_탈퇴하면_reporter는_null이고_author는_남은_참가자이다() throws Exception {
		대화_신고(구매자쿠키);
		탈퇴(구매자쿠키);

		MockHttpServletResponse res = 목록(관리자쿠키, "");

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode r = json(res).get(0);
		assertThat(r.get("reporter").isNull()).isTrue();
		assertThat(r.get("target").get("author").get("id").asLong()).isEqualTo(판매자.getId());
	}

	@Test
	void 글_댓글_중고_글_신고와_대화_신고가_섞여_있어도_목록은_200이다() throws Exception {
		User 글쓴이 = TestLogin.인증된_사용자(userRepository, jdbc, "acwriter", "연세대학교");
		Post post = postRepository.save(new Post("글 제목", "내용", 글쓴이, null, PostCategory.CHAT));
		Comment comment = commentRepository.save(new Comment(post, 글쓴이, "댓글 내용"));
		reportRepository.save(Report.ofPost(post, 구매자, ReportReason.SPAM, null));
		reportRepository.save(Report.ofComment(comment, 구매자, ReportReason.SPAM, null));
		reportRepository.save(Report.ofMarketItem(marketItemRepository.findById(글id).orElseThrow(), 구매자, ReportReason.SPAM, null));
		대화_신고(구매자쿠키);

		MockHttpServletResponse res = 목록(관리자쿠키, "");

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = json(res);
		assertThat(arr.size()).isEqualTo(4);
		assertThat(arr.get(0).get("target").get("type").asString()).isEqualTo("POST");
		assertThat(arr.get(1).get("target").get("type").asString()).isEqualTo("COMMENT");
		assertThat(arr.get(2).get("target").get("type").asString()).isEqualTo("MARKET_ITEM");
		assertThat(arr.get(3).get("target").get("type").asString()).isEqualTo("CONVERSATION");
	}

	// ============ N+1 ============

	// 대화 신고 + 글 신고를 섞어 n건 만들고(회원·방 모두 다름), 목록 GET 한 번에 나간 SQL 준비 횟수를 잰다
	// 대화 신고의 신고자는 구매자/판매자를 번갈아 쓴다. 신고자는 r.reporter fetch로 이미 읽히므로, 한쪽으로만 하면
	// 그쪽 참가자(cv.buyer 또는 cv.seller)의 fetch 누락이 가려진다. 섞어 두면 buyer·seller 누락을 둘 다 잡는다.
	private long 목록_SQL수(int n, int round) throws Exception {
		jdbc.update("delete from reports");
		jdbc.update("delete from conversations where id <> ?", 방id);
		for (int i = 0; i < n; i++) {
			User s = TestLogin.사용자(userRepository, "qs" + round + "_" + i);
			User b = TestLogin.사용자(userRepository, "qb" + round + "_" + i);
			Long room = jdbc.queryForObject("insert into conversations (market_item_id, item_title, buyer_id, seller_id, created_at, last_message_at) "
					+ "values (null, '제목', ?, ?, now(), now()) returning id", Long.class, b.getId(), s.getId());
			// 짝수 번째는 구매자가, 홀수 번째는 판매자가 신고한 방으로 만든다
			User 대화신고자 = (i % 2 == 0) ? b : s;
			reportRepository.save(Report.ofConversation(conversationRepository.findById(room).orElseThrow(), 대화신고자, ReportReason.SPAM, null));
			// 글쓴이는 방의 참가자와 다른 사람이어야 한다(같은 사람이면 목록 쿼리가 판매자를 이미 읽어 와서 N+1이 가려진다)
			// 글 신고의 신고자도 방 참가자가 아닌 제3자로 한다(참가자면 그 회원이 먼저 읽혀 buyer/seller fetch 누락이 가려진다)
			User x = TestLogin.사용자(userRepository, "qx" + round + "_" + i);
			User a = TestLogin.사용자(userRepository, "qa" + round + "_" + i);
			Post p = postRepository.save(new Post("제목" + i, "내용", a, null, PostCategory.CHAT));
			reportRepository.save(Report.ofPost(p, x, ReportReason.ABUSE, null));
		}
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		MockHttpServletResponse res;
		long count;
		try {
			stats.clear();
			res = 목록(관리자쿠키, "");
			count = stats.getPrepareStatementCount();
		} finally {
			stats.setStatisticsEnabled(false); // 다른 테스트에 영향이 없게 다시 끈다
		}
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).size()).isEqualTo(n * 2);
		return count;
	}

	@Test
	void 대화_신고가_2건이든_10건이든_SQL_실행_수가_같다() throws Exception {
		long small = 목록_SQL수(2, 1);
		long large = 목록_SQL수(10, 2);
		System.out.println("[쿼리수관찰:대화신고] 2건=" + small + ", 10건=" + large);
		assertThat(large).as("10건 SQL 수").isEqualTo(small);
	}

	// ============ 기각 ============

	@Test
	void 대화_신고를_기각하면_200과_DISMISSED이고_대상은_CONVERSATION이다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);

		MockHttpServletResponse res = 기각(관리자쿠키, reportId);

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("id").asLong()).isEqualTo(reportId);
		assertThat(b.get("status").asString()).isEqualTo("DISMISSED");
		assertThat(b.get("target").get("type").asString()).isEqualTo("CONVERSATION");
		assertThat(b.get("target").get("id").asLong()).isEqualTo(방id);
		assertThat(b.get("target").get("author").get("id").asLong()).isEqualTo(판매자.getId());
		// 목록: 대기에서는 사라지고 기각 목록에 나온다
		assertThat(json(목록(관리자쿠키, "")).size()).isZero();
		JsonNode dismissed = json(목록(관리자쿠키, "?status=DISMISSED"));
		assertThat(dismissed.size()).isEqualTo(1);
		assertThat(dismissed.get(0).get("target").get("type").asString()).isEqualTo("CONVERSATION");
		// 기각해도 방과 쪽지는 그대로다(삭제 처리 같은 건 없다)
		assertThat(jdbc.queryForObject("select count(*) from conversations where id = ?", Integer.class, 방id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from messages where conversation_id = ?", Integer.class, 방id)).isEqualTo(1);
	}

	@Test
	void 이미_기각한_대화_신고를_다시_기각하면_409이다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);
		assertThat(기각(관리자쿠키, reportId).getStatus()).isEqualTo(200);
		assertThat(기각(관리자쿠키, reportId).getStatus()).isEqualTo(409);
	}

	@Test
	void 관리자가_아니면_대화_신고_목록과_기각은_403이다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);

		assertThat(목록(구매자쿠키, "").getStatus()).isEqualTo(403);
		assertThat(기각(구매자쿠키, reportId).getStatus()).isEqualTo(403);
		assertThat(목록(null, "").getStatus()).isEqualTo(401);
	}

	@Test
	void 기각한_뒤_같은_사람이_같은_방을_다시_신고하면_409이다_기존_신고_규칙과_같다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);
		기각(관리자쿠키, reportId);

		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(409);
	}
}
