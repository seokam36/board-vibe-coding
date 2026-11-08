package com.example.demo.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

// 리뷰 참고 사항 두 가지: 숫자 reason 거부 여부, status 빈 값 처리
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ReportInputEdgeTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PostRepository postRepository;
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

	User 관리자;
	User 글쓴이;
	User 신고자;
	Post 글;

	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	@BeforeEach
	void 준비() {
		정리();
		관리자 = TestLogin.관리자(userRepository, jdbc, "admin");
		글쓴이 = TestLogin.사용자(userRepository, "writer");
		신고자 = TestLogin.사용자(userRepository, "reporter");
		글 = postRepository.save(new Post("제목", "내용", 글쓴이, null, PostCategory.CHAT));
	}

	// 숫자 enum({"reason":0})은 "몇 번째 값"으로 받지 않고 400으로 거절하고 DB에 저장하지 않는다
	@Test
	void 신고_reason에_숫자_0을_넣으면_400이고_저장되지_않는다() throws Exception {
		Cookie c = TestLogin.쿠키(tokenService, 신고자);
		MockHttpServletResponse res = mockMvc.perform(post("/posts/" + 글.getId() + "/reports").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":0}").cookie(c)).andReturn().getResponse();

		assertThat(res.getStatus()).as("숫자 enum 신고 응답").isEqualTo(400);
		assertThat(jdbc.queryForObject("select count(*) from reports", Long.class)).isZero();
	}

	// 숫자 문자열 "0"도 말머리 이름이 아니므로 400이어야 한다 (기존 동작 확인)
	@Test
	void 신고_reason에_숫자_문자열_0을_넣으면_400이다() throws Exception {
		Cookie c = TestLogin.쿠키(tokenService, 신고자);
		MockHttpServletResponse res = mockMvc.perform(post("/posts/" + 글.getId() + "/reports").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"0\"}").cookie(c)).andReturn().getResponse();

		assertThat(res.getStatus()).isEqualTo(400);
		assertThat(jdbc.queryForObject("select count(*) from reports", Long.class)).isZero();
	}

	// status 파라미터가 비어 있으면(?status=) 기본값 PENDING으로 취급해 200 + 대기 중 신고만 돌려줘야 한다
	@Test
	void 관리자_신고_목록에_status가_빈_값이면_200이고_PENDING_목록이다() throws Exception {
		Report 대기 = reportRepository.save(Report.ofPost(글, 신고자, ReportReason.SPAM, null));
		Report 기각됨 = reportRepository.save(Report.ofPost(글, 글쓴이, ReportReason.ABUSE, null));
		jdbc.update("update reports set status = 'DISMISSED' where id = ?", 기각됨.getId());

		MockHttpServletResponse res = mockMvc
				.perform(get("/admin/reports?status=").cookie(TestLogin.관리자_쿠키(tokenService, 관리자))).andReturn()
				.getResponse();

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = om.readTree(res.getContentAsString());
		assertThat(arr.size()).isEqualTo(1);
		assertThat(arr.get(0).get("id").asLong()).isEqualTo(대기.getId());
	}
}
