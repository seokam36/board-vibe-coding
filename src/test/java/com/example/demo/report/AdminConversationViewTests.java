package com.example.demo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.Map;

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
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.comment.domain.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.market.repository.MarketItemRepository;
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

// S14-f2: 관리자가 신고된 대화를 읽는 API(GET /admin/reports/{id}/conversation)를 검사한다.
// 핵심: 방 전체가 보인다 / 읽음 처리가 안 된다 / 대화 신고만 열린다 / 일반 회원 403 / 쿼리 수 고정 /
// 그리고 같은 관리자도 GET /conversations/{id}는 여전히 404(결정 ⑥)이다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AdminConversationViewTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired CommentRepository commentRepository;
	@Autowired MarketItemRepository marketItemRepository;
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
		관리자 = TestLogin.관리자(userRepository, jdbc, "acvadmin");
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "acvseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "acvbuyer", "연세대학교");
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		long[] ids = ConversationReportFixture.글과_방_만들기(mockMvc, om, 판매자쿠키, 구매자쿠키, "관리자가 읽을 제목");
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

	// 공용 도우미(ConversationReportFixture)로 넘기는 얇은 껍데기. 호출부를 짧게 유지한다.
	private MockHttpServletResponse 보기(Cookie cookie, Object reportId) throws Exception {
		return ConversationReportFixture.보기(mockMvc, cookie, reportId);
	}

	private JsonNode json(MockHttpServletResponse res) throws Exception {
		return ConversationReportFixture.json(om, res);
	}

	private long 대화_신고(Cookie 쿠키) throws Exception {
		return ConversationReportFixture.대화_신고(mockMvc, om, 쿠키, 방id);
	}

	private void 답장(Cookie 쿠키, String 내용) throws Exception {
		ConversationReportFixture.답장(mockMvc, 쿠키, 방id, "\"" + 내용 + "\"");
	}

	private void 탈퇴(Cookie 쿠키) throws Exception {
		ConversationReportFixture.탈퇴(mockMvc, 쿠키);
	}

	// ============ 성공: 모양과 범위 ============

	@Test
	void 신고된_대화의_방_전체를_id_오름차순으로_돌려준다_신고_뒤_쪽지와_신고하지_않은_상대의_쪽지도_보인다() throws Exception {
		답장(판매자쿠키, "둘째"); // 신고 전 답장
		long reportId = 대화_신고(구매자쿠키);
		답장(판매자쿠키, "신고 뒤 판매자 답장"); // 신고 뒤 쪽지, 신고하지 않은 쪽(판매자)의 쪽지
		답장(구매자쿠키, "신고 뒤 구매자 쪽지");

		MockHttpServletResponse res = 보기(관리자쿠키, reportId);

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("reportId").asLong()).isEqualTo(reportId);
		assertThat(b.get("conversationId").asLong()).isEqualTo(방id);
		assertThat(b.get("itemTitle").asString()).isEqualTo("관리자가 읽을 제목");
		assertThat(b.get("buyer").get("id").asLong()).isEqualTo(구매자.getId());
		assertThat(b.get("buyer").get("nickname").asString()).isEqualTo("acvbuyer");
		assertThat(b.get("seller").get("id").asLong()).isEqualTo(판매자.getId());
		assertThat(b.get("seller").get("nickname").asString()).isEqualTo("acvseller");
		JsonNode m = b.get("messages");
		assertThat(m.size()).isEqualTo(4);
		assertThat(m.get(0).get("content").asString()).isEqualTo("첫째");
		assertThat(m.get(0).get("sender").get("id").asLong()).isEqualTo(구매자.getId());
		assertThat(m.get(1).get("content").asString()).isEqualTo("둘째");
		assertThat(m.get(1).get("sender").get("id").asLong()).isEqualTo(판매자.getId());
		assertThat(m.get(2).get("content").asString()).isEqualTo("신고 뒤 판매자 답장");
		assertThat(m.get(3).get("content").asString()).isEqualTo("신고 뒤 구매자 쪽지");
		for (int i = 0; i < 4; i++) {
			assertThat(m.get(i).get("createdAt").asString()).isNotBlank();
			if (i > 0) {
				assertThat(m.get(i).get("id").asLong()).as("id 오름차순").isGreaterThan(m.get(i - 1).get("id").asLong());
			}
			// 읽음 여부·mine은 응답에 없다
			assertThat(m.get(i).propertyNames()).containsExactlyInAnyOrder("id", "sender", "content", "createdAt");
		}
		assertThat(b.propertyNames()).containsExactlyInAnyOrder("reportId", "conversationId", "itemTitle", "buyer", "seller", "messages");
	}

	@Test
	void 기각된_신고도_계속_읽을_수_있다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);
		int st = mockMvc.perform(put("/admin/reports/" + reportId + "/dismiss").with(csrf()).cookie(관리자쿠키)).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(200);

		MockHttpServletResponse res = 보기(관리자쿠키, reportId);

		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).get("messages").size()).isEqualTo(1);
	}

	@Test
	void 글이_삭제되고_상대가_탈퇴한_방도_200이다_제목은_방에_복사돼_있다() throws Exception {
		답장(판매자쿠키, "판매자 답장");
		long reportId = 대화_신고(구매자쿠키);
		탈퇴(판매자쿠키); // 글도 같이 삭제되고 방의 판매자 칸·판매자가 보낸 쪽지의 sender가 NULL

		MockHttpServletResponse res = 보기(관리자쿠키, reportId);

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("itemTitle").asString()).isEqualTo("관리자가 읽을 제목");
		assertThat(b.get("seller").isNull()).isTrue();
		assertThat(b.get("buyer").get("id").asLong()).isEqualTo(구매자.getId());
		assertThat(b.get("messages").size()).isEqualTo(2);
		assertThat(b.get("messages").get(0).get("sender").get("id").asLong()).isEqualTo(구매자.getId());
		assertThat(b.get("messages").get(1).get("sender").isNull()).as("탈퇴한 판매자의 쪽지").isTrue();
		assertThat(b.get("messages").get(1).get("content").asString()).isEqualTo("판매자 답장");
	}

	@Test
	void 글만_삭제된_방도_200이다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);
		int st = mockMvc.perform(delete("/market-items/" + 글id).with(csrf()).cookie(판매자쿠키)).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(204);

		MockHttpServletResponse res = 보기(관리자쿠키, reportId);

		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).get("itemTitle").asString()).isEqualTo("관리자가 읽을 제목");
		assertThat(json(res).get("seller").get("id").asLong()).isEqualTo(판매자.getId());
	}

	// ============ 읽음 처리 안 됨 ============

	@Test
	void 관리자가_읽어도_read_at이_바뀌지_않는다() throws Exception {
		답장(판매자쿠키, "판매자 답장"); // 구매자 입장에서 안 읽은 쪽지
		long reportId = 대화_신고(구매자쿠키);
		// 하나는 이미 읽은 시각이 찍힌 상태로 만들어 둔다(안 읽음과 읽음이 섞인 방)
		jdbc.update("update messages set read_at = '2026-01-01T00:00:00Z' where content = '첫째'");
		List<Map<String, Object>> before = jdbc.queryForList("select id, read_at from messages order by id");
		assertThat(before).hasSize(2);

		assertThat(보기(관리자쿠키, reportId).getStatus()).isEqualTo(200);
		assertThat(보기(관리자쿠키, reportId).getStatus()).isEqualTo(200);

		assertThat(jdbc.queryForList("select id, read_at from messages order by id")).isEqualTo(before);
		// 구매자의 안 읽은 쪽지 수도 그대로(판매자 답장 1통)
		MockHttpServletResponse unread = mockMvc.perform(get("/conversations/unread-count").cookie(구매자쿠키)).andReturn().getResponse();
		assertThat(json(unread).get("count").asLong()).isEqualTo(1);
	}

	// ============ 결정 ⑥: 일반 통로는 관리자에게 계속 404 ============

	@Test
	void 같은_관리자가_대화_보기_API로_그_방을_부르면_여전히_404이다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);
		assertThat(보기(관리자쿠키, reportId).getStatus()).isEqualTo(200); // 관리자 통로는 열려 있다

		int st = mockMvc.perform(get("/conversations/" + 방id).cookie(관리자쿠키)).andReturn().getResponse().getStatus();

		assertThat(st).isEqualTo(404);
	}

	// ============ 권한 ============

	@Test
	void 일반_회원은_403_비로그인은_401이다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);

		assertThat(보기(구매자쿠키, reportId).getStatus()).as("신고자(구매자)").isEqualTo(403);
		assertThat(보기(판매자쿠키, reportId).getStatus()).as("상대(판매자)").isEqualTo(403);
		assertThat(보기(null, reportId).getStatus()).isEqualTo(401);
	}

	@Test
	void 관리자_토큰이지만_회원이_탈퇴했으면_401_강등됐으면_403이다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);

		jdbc.update("update users set role = 'USER' where id = ?", 관리자.getId());
		assertThat(보기(관리자쿠키, reportId).getStatus()).as("DB에서 강등").isEqualTo(403);

		jdbc.update("delete from users where id = ?", 관리자.getId());
		assertThat(보기(관리자쿠키, reportId).getStatus()).as("DB에서 삭제").isEqualTo(401);
	}

	// ============ 404 / 400 ============

	@Test
	void 없는_신고는_404이고_숫자가_아닌_id는_400이다() throws Exception {
		assertThat(보기(관리자쿠키, 999999999L).getStatus()).isEqualTo(404);
		assertThat(보기(관리자쿠키, "abc").getStatus()).isEqualTo(400);
	}

	@Test
	void 글_댓글_중고_글_신고의_id는_404이다_대화_신고만_열린다() throws Exception {
		User 글쓴이 = TestLogin.인증된_사용자(userRepository, jdbc, "acvwriter", "연세대학교");
		Post post = postRepository.save(new Post("글 제목", "내용", 글쓴이, null, PostCategory.CHAT));
		Comment comment = commentRepository.save(new Comment(post, 글쓴이, "댓글 내용"));
		Report 글신고 = reportRepository.save(Report.ofPost(post, 구매자, ReportReason.SPAM, null));
		Report 댓글신고 = reportRepository.save(Report.ofComment(comment, 구매자, ReportReason.SPAM, null));
		Report 중고신고 = reportRepository.save(Report.ofMarketItem(marketItemRepository.findById(글id).orElseThrow(), 구매자, ReportReason.SPAM, null));

		assertThat(보기(관리자쿠키, 글신고.getId()).getStatus()).as("글 신고").isEqualTo(404);
		assertThat(보기(관리자쿠키, 댓글신고.getId()).getStatus()).as("댓글 신고").isEqualTo(404);
		assertThat(보기(관리자쿠키, 중고신고.getId()).getStatus()).as("중고 글 신고").isEqualTo(404);
	}

	// ============ 쿼리 수 ============

	// 방의 쪽지를 n통으로 맞추고(보낸 사람은 전부 서로 다른 제3자), 읽기 한 번에 나간 SQL 준비 횟수를 잰다.
	// 보낸 사람이 방 참가자(구매자·판매자)면 이미 읽혀 있는 회원이라 sender fetch가 빠져도 추가 쿼리가 안 나가 가려진다.
	// 그래서 제3자로 해서 sender fetch 누락을 잡는다.
	private long 읽기_SQL수(long reportId, int n, int round) throws Exception {
		jdbc.update("delete from messages where conversation_id = ?", 방id);
		for (int i = 0; i < n; i++) {
			User u = TestLogin.사용자(userRepository, "qm" + round + "_" + i);
			jdbc.update("insert into messages (conversation_id, sender_id, content, created_at) values (?, ?, ?, now())", 방id, u.getId(), "쪽지" + i);
		}
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		MockHttpServletResponse res;
		long count;
		try {
			stats.clear();
			res = 보기(관리자쿠키, reportId);
			count = stats.getPrepareStatementCount();
		} finally {
			stats.setStatisticsEnabled(false);
		}
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).get("messages").size()).isEqualTo(n);
		return count;
	}

	@Test
	void 쪽지가_1통이든_50통이든_SQL_실행_수가_같다() throws Exception {
		long reportId = 대화_신고(구매자쿠키);
		long small = 읽기_SQL수(reportId, 1, 1);
		long large = 읽기_SQL수(reportId, 50, 2);
		System.out.println("[쿼리수관찰:관리자대화보기] 1통=" + small + ", 50통=" + large);
		assertThat(large).as("50통 SQL 수").isEqualTo(small);
		// 실측 3번 = 관리자 회원 재확인 1 + 신고(방·참가자 포함) 1 + 쪽지(보낸 사람 포함) 1
		assertThat(small).as("절대 SQL 수").isEqualTo(3L);
	}
}
