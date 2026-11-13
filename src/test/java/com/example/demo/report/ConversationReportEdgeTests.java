package com.example.demo.report;

import static com.example.demo.report.ConversationReportFixture.본문;
import static com.example.demo.report.ConversationReportFixture.신고;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.report.domain.Report;
import com.example.demo.report.domain.ReportReason;
import com.example.demo.report.repository.ReportRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S14-f 빈틈 보강(tester): build가 안 다룬 경계값·섞인 목록·관리자 참가자 장면을 검사한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationReportEdgeTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired ReportRepository reportRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;

	User 판매자, 구매자, 관리자;
	Cookie 판매자쿠키, 구매자쿠키, 관리자쿠키;
	long 글id, 방id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "edseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "edbuyer", "연세대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "edadmin");
		TestLogin.학교_인증_처리(jdbc, "edadmin", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		long[] ids = ConversationReportFixture.글과_방_만들기(mockMvc, om, 판매자쿠키, 구매자쿠키, "경계 제목");
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

	private JsonNode json(MockHttpServletResponse res) throws Exception {
		return om.readTree(res.getContentAsString());
	}

	private MockHttpServletResponse 목록(String query) throws Exception {
		return mockMvc.perform(get("/admin/reports" + query).cookie(관리자쿠키)).andReturn().getResponse();
	}

	// ===== 방 id 경계 =====

	@Test
	void 방_id가_0이면_404이고_저장되지_않는다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 0, 본문("SPAM")).getStatus()).isEqualTo(404);
		assertThat(jdbc.queryForObject("select count(*) from reports", Integer.class)).isZero();
	}

	@Test
	void 방_id가_음수여도_500이_아니라_404이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, -1, 본문("SPAM")).getStatus()).isEqualTo(404);
	}

	@Test
	void 방_id가_Long_최대값이어도_404이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, Long.MAX_VALUE, 본문("SPAM")).getStatus()).isEqualTo(404);
	}

	@Test
	void 방_id가_Long_범위를_넘으면_500이_아니라_400이다() throws Exception {
		int st = 신고(mockMvc, 구매자쿠키, "99999999999999999999", 본문("SPAM")).getStatus();
		assertThat(st).isEqualTo(400);
	}

	// ===== detail 경계 =====

	@Test
	void detail이_정확히_300자면_201이고_그대로_저장된다() throws Exception {
		String d = "가".repeat(300);
		MockHttpServletResponse res = 신고(mockMvc, 구매자쿠키, 방id, "{\"reason\":\"SPAM\",\"detail\":\"" + d + "\"}");
		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(jdbc.queryForObject("select detail from reports", String.class)).isEqualTo(d);
	}

	// ===== 관리자가 참가자인 방 =====

	@Test
	void 관리자가_자기가_참가자인_방을_신고하면_201이다() throws Exception {
		// 관리자가 구매자로 새 방을 연다 (남의 방이 아니라 자기 방이므로 404가 아니다)
		long[] ids = ConversationReportFixture.글과_방_만들기(mockMvc, om, 판매자쿠키, 관리자쿠키, "관리자가 산 글");
		MockHttpServletResponse res = 신고(mockMvc, 관리자쿠키, ids[1], 본문("OTHER"));
		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(json(res).get("target").get("id").asLong()).isEqualTo(ids[1]);
	}

	// ===== 같은 방을 구매자·판매자가 모두 신고 =====

	@Test
	void 구매자와_판매자가_모두_신고하면_관리자_목록에_2건이고_author가_서로_상대이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("ABUSE")).getStatus()).isEqualTo(201);
		assertThat(신고(mockMvc, 판매자쿠키, 방id, 본문("ABUSE")).getStatus()).isEqualTo(201);

		JsonNode arr = json(목록(""));
		assertThat(arr.size()).isEqualTo(2);
		assertThat(arr.get(0).get("reporter").get("id").asLong()).isEqualTo(구매자.getId());
		assertThat(arr.get(0).get("target").get("author").get("id").asLong()).isEqualTo(판매자.getId());
		assertThat(arr.get(1).get("reporter").get("id").asLong()).isEqualTo(판매자.getId());
		assertThat(arr.get(1).get("target").get("author").get("id").asLong()).isEqualTo(구매자.getId());
	}

	// ===== 글 신고와 섞인 상태 필터 =====

	@Test
	void 글_신고와_대화_신고가_섞이면_PENDING_DISMISSED_필터가_각각_맞는_것만_돌려준다() throws Exception {
		Post post = postRepository.save(new Post("섞기용", "내용", 판매자, null, PostCategory.CHAT));
		long 글신고 = reportRepository.save(Report.ofPost(post, 구매자, ReportReason.SPAM, null)).getId();
		long 대화신고 = json(신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM"))).get("id").asLong();

		// 대화 신고만 기각한다
		assertThat(mockMvc.perform(put("/admin/reports/" + 대화신고 + "/dismiss").with(csrf()).cookie(관리자쿠키))
				.andReturn().getResponse().getStatus()).isEqualTo(200);

		JsonNode pending = json(목록("?status=PENDING"));
		assertThat(pending.size()).isEqualTo(1);
		assertThat(pending.get(0).get("id").asLong()).isEqualTo(글신고);
		assertThat(pending.get(0).get("target").get("type").asString()).isEqualTo("POST");

		JsonNode dismissed = json(목록("?status=DISMISSED"));
		assertThat(dismissed.size()).isEqualTo(1);
		assertThat(dismissed.get(0).get("id").asLong()).isEqualTo(대화신고);
		assertThat(dismissed.get(0).get("target").get("type").asString()).isEqualTo("CONVERSATION");
	}

	@Test
	void 없는_신고_id를_기각하면_404이다() throws Exception {
		int st = mockMvc.perform(put("/admin/reports/999999999/dismiss").with(csrf()).cookie(관리자쿠키))
				.andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(404);
	}

	// ===== 로그인한 참가자(쿠키 + CSRF 있음)가 JSON이 아닌 본문을 보낸 경우 =====

	@Test
	void Content_Type이_JSON이_아니면_415이다() throws Exception {
		int st = mockMvc.perform(post("/conversations/" + 방id + "/reports").with(csrf()).cookie(구매자쿠키)
				.contentType(MediaType.TEXT_PLAIN).content("x")).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(415);
	}
}
