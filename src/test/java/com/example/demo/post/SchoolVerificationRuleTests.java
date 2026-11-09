package com.example.demo.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.comment.domain.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.domain.School;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// S10-d 학교 인증 전 제한 완료 조건 검사
// 미인증 회원은 읽기만, 쓰기(글·댓글·공감·신고)는 인증 후 + 인증한 학교의 글에만 가능한지 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SchoolVerificationRuleTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PostRepository postRepository;
	@Autowired
	CommentRepository commentRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	SchoolRepository schoolRepository;
	@Autowired
	TokenService tokenService;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	JwtEncoder jwtEncoder;

	School 연세대;
	School 고려대;
	User 글쓴이; // 연세대 인증 회원(연세대 글·댓글 주인)
	User 인증회원; // 연세대 인증 회원(요청하는 쪽)
	User 미인증회원;
	Cookie 인증쿠키;
	Cookie 미인증쿠키;
	Post 연세글;
	Post 고려글;
	Post 학교없는글;
	Comment 연세댓글;
	Comment 고려댓글;

	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	@BeforeEach
	void 준비() {
		정리();
		연세대 = TestLogin.학교(schoolRepository, "연세대학교");
		고려대 = TestLogin.학교(schoolRepository, "고려대학교");
		글쓴이 = TestLogin.인증된_사용자(userRepository, jdbc, "writer", "연세대학교");
		인증회원 = TestLogin.인증된_사용자(userRepository, jdbc, "verified", "연세대학교");
		미인증회원 = TestLogin.사용자(userRepository, "unverified");
		인증쿠키 = TestLogin.쿠키(tokenService, 인증회원);
		미인증쿠키 = TestLogin.쿠키(tokenService, 미인증회원);
		연세글 = postRepository.save(new Post("연세 글", "내용", 글쓴이, 연세대, PostCategory.CHAT));
		고려글 = postRepository.save(new Post("고려 글", "내용", 글쓴이, 고려대, PostCategory.CHAT));
		학교없는글 = postRepository.save(new Post("옛 글", "내용", 글쓴이, 연세대, PostCategory.CHAT));
		// 학교 미지정 옛 글: school_id를 NULL로 되돌린다
		jdbc.update("update posts set school_id = null where id = ?", 학교없는글.getId());
		연세댓글 = commentRepository.save(new Comment(연세글, 글쓴이, "연세 댓글"));
		고려댓글 = commentRepository.save(new Comment(고려글, 글쓴이, "고려 댓글"));
	}

	// ---------- 도우미 ----------

	private MockHttpServletResponse 요청(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
			Cookie cookie, String body) throws Exception {
		req = req.with(csrf());
		if (body != null) {
			req = req.contentType(MediaType.APPLICATION_JSON).content(body);
		}
		if (cookie != null) {
			req = req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private int 글쓰기(Cookie c, Object schoolId) throws Exception {
		return 요청(post("/posts"), c,
				"{\"title\":\"새 글\",\"content\":\"본문\",\"schoolId\":" + schoolId + ",\"category\":\"CHAT\"}").getStatus();
	}

	private int 댓글(Cookie c, Object postId) throws Exception {
		return 요청(post("/posts/" + postId + "/comments"), c, "{\"content\":\"댓글\"}").getStatus();
	}

	private int 공감(Cookie c, Object postId) throws Exception {
		return 요청(post("/posts/" + postId + "/likes"), c, null).getStatus();
	}

	private int 글신고(Cookie c, Object postId) throws Exception {
		return 요청(post("/posts/" + postId + "/reports"), c, "{\"reason\":\"SPAM\"}").getStatus();
	}

	private int 댓글신고(Cookie c, Object commentId) throws Exception {
		return 요청(post("/comments/" + commentId + "/reports"), c, "{\"reason\":\"SPAM\"}").getStatus();
	}

	private long 행수(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private void 미인증으로_되돌림(User u) {
		jdbc.update("update users set verified_school_id = null, verified_at = null, school_email_hash = null where id = ?",
				u.getId());
	}

	// ============ 조건 1: 미인증 회원은 쓰기 403, 행 수 변화 없음 ============

	@Test
	void 조건1_미인증_회원의_글쓰기는_403이고_글이_늘지_않는다() throws Exception {
		long before = 행수("posts");
		assertThat(글쓰기(미인증쿠키, 연세대.getId())).isEqualTo(403);
		assertThat(행수("posts")).isEqualTo(before);
	}

	@Test
	void 조건1_미인증_회원의_댓글은_403이고_댓글이_늘지_않는다() throws Exception {
		long before = 행수("comments");
		assertThat(댓글(미인증쿠키, 연세글.getId())).isEqualTo(403);
		assertThat(행수("comments")).isEqualTo(before);
	}

	@Test
	void 조건1_미인증_회원의_공감은_403이고_공감이_늘지_않는다() throws Exception {
		assertThat(공감(미인증쿠키, 연세글.getId())).isEqualTo(403);
		assertThat(행수("post_likes")).isZero();
	}

	@Test
	void 조건1_미인증_회원의_글_신고는_403이고_신고가_늘지_않는다() throws Exception {
		assertThat(글신고(미인증쿠키, 연세글.getId())).isEqualTo(403);
		assertThat(행수("reports")).isZero();
	}

	@Test
	void 조건1_미인증_회원의_댓글_신고는_403이고_신고가_늘지_않는다() throws Exception {
		assertThat(댓글신고(미인증쿠키, 연세댓글.getId())).isEqualTo(403);
		assertThat(행수("reports")).isZero();
	}

	// ============ 조건 2: 연세대 인증 회원, 연세대 대상은 성공 ============

	@Test
	void 조건2_연세대_인증_회원이_연세대_글쓰기는_201이다() throws Exception {
		long before = 행수("posts");
		assertThat(글쓰기(인증쿠키, 연세대.getId())).isEqualTo(201);
		assertThat(행수("posts")).isEqualTo(before + 1);
	}

	@Test
	void 조건2_연세대_인증_회원이_연세대_글에_댓글_공감_글신고_댓글신고는_모두_201이다() throws Exception {
		assertThat(댓글(인증쿠키, 연세글.getId())).isEqualTo(201);
		assertThat(공감(인증쿠키, 연세글.getId())).isEqualTo(201);
		assertThat(글신고(인증쿠키, 연세글.getId())).isEqualTo(201);
		assertThat(댓글신고(인증쿠키, 연세댓글.getId())).isEqualTo(201);
		assertThat(행수("post_likes")).isEqualTo(1);
		assertThat(행수("reports")).isEqualTo(2);
	}

	// ============ 조건 3: 다른 학교·학교 없는 글은 403 ============

	@Test
	void 조건3_연세대_인증_회원이_고려대_schoolId로_글쓰기는_403이다() throws Exception {
		long before = 행수("posts");
		assertThat(글쓰기(인증쿠키, 고려대.getId())).isEqualTo(403);
		assertThat(행수("posts")).isEqualTo(before);
	}

	@Test
	void 조건3_연세대_인증_회원이_고려대_글에_댓글_공감_신고_댓글신고는_모두_403이다() throws Exception {
		long comments = 행수("comments");
		assertThat(댓글(인증쿠키, 고려글.getId())).isEqualTo(403);
		assertThat(공감(인증쿠키, 고려글.getId())).isEqualTo(403);
		assertThat(글신고(인증쿠키, 고려글.getId())).isEqualTo(403);
		assertThat(댓글신고(인증쿠키, 고려댓글.getId())).isEqualTo(403);
		assertThat(행수("comments")).isEqualTo(comments);
		assertThat(행수("post_likes")).isZero();
		assertThat(행수("reports")).isZero();
	}

	@Test
	void 조건3_인증_회원도_학교_미지정_글에_댓글_공감_신고는_403이고_저장되지_않는다() throws Exception {
		long comments = 행수("comments");
		assertThat(댓글(인증쿠키, 학교없는글.getId())).isEqualTo(403);
		assertThat(공감(인증쿠키, 학교없는글.getId())).isEqualTo(403);
		assertThat(글신고(인증쿠키, 학교없는글.getId())).isEqualTo(403);
		assertThat(행수("comments")).isEqualTo(comments);
		assertThat(행수("post_likes")).isZero();
		assertThat(행수("reports")).isZero();
	}

	@Test
	void 조건3_학교_미지정_글의_댓글_신고는_403이다() throws Exception {
		Comment 옛댓글 = commentRepository.save(new Comment(학교없는글, 글쓴이, "옛 댓글"));
		assertThat(댓글신고(인증쿠키, 옛댓글.getId())).isEqualTo(403);
		assertThat(행수("reports")).isZero();
	}

	// ============ 조건 4: 검사 순서 ============

	@Test
	void 조건4_미인증_회원이_없는_글에_댓글을_달면_404가_아니라_403이다() throws Exception {
		assertThat(댓글(미인증쿠키, 999999999L)).isEqualTo(403);
	}

	@Test
	void 조건4_인증_회원이_없는_글에_댓글_공감은_404다() throws Exception {
		assertThat(댓글(인증쿠키, 999999999L)).isEqualTo(404);
		assertThat(공감(인증쿠키, 999999999L)).isEqualTo(404);
	}

	@Test
	void 조건4_인증_회원이_없는_schoolId로_글쓰기는_400이다() throws Exception {
		assertThat(글쓰기(인증쿠키, 999999999L)).isEqualTo(400);
	}

	// ============ 조건 5: 인증과 무관한 동작은 미인증이어도 그대로 ============

	@Test
	void 조건5_인증을_잃은_회원도_자기_옛_글_수정_삭제와_옛_댓글_수정_삭제가_된다() throws Exception {
		// 인증 회원이 쓴 글·댓글을 만든 뒤 DB에서 인증 칸을 NULL로 돌린다
		Post 내글 = postRepository.save(new Post("내 글", "내용", 인증회원, 연세대, PostCategory.CHAT));
		Post 내글2 = postRepository.save(new Post("내 글2", "내용", 인증회원, 연세대, PostCategory.CHAT));
		Comment 내댓글 = commentRepository.save(new Comment(연세글, 인증회원, "내 댓글"));
		Comment 내댓글2 = commentRepository.save(new Comment(연세글, 인증회원, "내 댓글2"));
		미인증으로_되돌림(인증회원);

		assertThat(요청(put("/posts/" + 내글.getId()), 인증쿠키, "{\"title\":\"수정\",\"content\":\"수정 본문\",\"category\":\"CHAT\"}")
				.getStatus()).isEqualTo(200);
		assertThat(요청(delete("/posts/" + 내글2.getId()), 인증쿠키, null).getStatus()).isEqualTo(204);
		assertThat(요청(put("/comments/" + 내댓글.getId()), 인증쿠키, "{\"content\":\"수정 댓글\"}").getStatus()).isEqualTo(200);
		assertThat(요청(delete("/comments/" + 내댓글2.getId()), 인증쿠키, null).getStatus()).isEqualTo(204);
		assertThat(행수("posts where id = " + 내글2.getId())).isZero();
		assertThat(행수("comments where id = " + 내댓글2.getId())).isZero();
	}

	@Test
	void 조건5_인증을_잃은_회원도_공감_취소와_내가_눌렀는지_GET이_된다() throws Exception {
		assertThat(공감(인증쿠키, 연세글.getId())).isEqualTo(201);
		미인증으로_되돌림(인증회원);

		MockHttpServletResponse me = mockMvc.perform(get("/posts/" + 연세글.getId() + "/likes/me").cookie(인증쿠키)).andReturn()
				.getResponse();
		assertThat(me.getStatus()).isEqualTo(200);
		assertThat(me.getContentAsString()).contains("true");

		assertThat(요청(delete("/posts/" + 연세글.getId() + "/likes"), 인증쿠키, null).getStatus()).isEqualTo(200);
		assertThat(행수("post_likes")).isZero();
	}

	@Test
	void 조건5_미인증_회원도_글_목록과_상세_댓글_목록_GET은_200이다() throws Exception {
		assertThat(mockMvc.perform(get("/posts").cookie(미인증쿠키)).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(get("/posts/" + 연세글.getId()).cookie(미인증쿠키)).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		assertThat(mockMvc.perform(get("/posts/" + 연세글.getId() + "/comments").cookie(미인증쿠키)).andReturn().getResponse()
				.getStatus()).isEqualTo(200);
		assertThat(mockMvc.perform(get("/posts/" + 연세글.getId() + "/likes/me").cookie(미인증쿠키)).andReturn().getResponse()
				.getStatus()).isEqualTo(200);
	}

	// ============ 조건 6: 미인증 관리자 ============

	@Test
	void 조건6_미인증_관리자는_남의_글_댓글_삭제_신고목록_기각이_되지만_글쓰기는_403이다() throws Exception {
		// 신고 하나를 미리 만든다(인증 회원이 연세대 글 신고)
		assertThat(글신고(인증쿠키, 연세글.getId())).isEqualTo(201);
		Long reportId = jdbc.queryForObject("select id from reports", Long.class);

		User admin = TestLogin.관리자(userRepository, jdbc, "admin"); // 인증 칸은 NULL
		Cookie ac = TestLogin.관리자_쿠키(tokenService, admin);

		assertThat(mockMvc.perform(get("/admin/reports").cookie(ac)).andReturn().getResponse().getStatus()).isEqualTo(200);
		assertThat(요청(put("/admin/reports/" + reportId + "/dismiss"), ac, null).getStatus()).isEqualTo(200);
		assertThat(요청(delete("/comments/" + 고려댓글.getId()), ac, null).getStatus()).isEqualTo(204);
		assertThat(요청(delete("/posts/" + 고려글.getId()), ac, null).getStatus()).isEqualTo(204);

		assertThat(글쓰기(ac, 연세대.getId())).isEqualTo(403);
	}

	// ============ 조건 7: 유령 토큰은 401 ============

	@Test
	void 조건7_유령_토큰으로_글쓰기_댓글_공감_신고는_401이다() throws Exception {
		Cookie ghost = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(글쓰기(ghost, 연세대.getId())).isEqualTo(401);
		assertThat(댓글(ghost, 연세글.getId())).isEqualTo(401);
		assertThat(공감(ghost, 연세글.getId())).isEqualTo(401);
		assertThat(글신고(ghost, 연세글.getId())).isEqualTo(401);
		assertThat(댓글신고(ghost, 연세댓글.getId())).isEqualTo(401);
	}

	// ============ 조건 8: 인증 여부는 토큰이 아니라 DB에서 확인 ============

	@Test
	void 조건8_인증_직후_같은_토큰으로_바로_글쓰기가_201이다() throws Exception {
		// 인증 전에는 403
		assertThat(글쓰기(미인증쿠키, 연세대.getId())).isEqualTo(403);

		// DB 인증 칸만 채우고, 토큰은 재발급하지 않는다
		TestLogin.학교_인증_처리(jdbc, "unverified", "연세대학교");

		assertThat(글쓰기(미인증쿠키, 연세대.getId())).isEqualTo(201);
	}
}
