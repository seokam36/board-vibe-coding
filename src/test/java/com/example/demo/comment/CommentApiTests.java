package com.example.demo.comment;

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
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S8-a 댓글 API 완료 조건 검사 (작성·목록·수정·삭제·글 삭제 연쇄·탈퇴)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class CommentApiTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PostRepository postRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	TokenService tokenService;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ObjectMapper om;

	User 글쓴이;
	User 다른회원;
	Cookie 글쓴이쿠키;
	Cookie 다른쿠키;
	Post 글;

	// 글을 먼저 지우면 댓글도 DB가 CASCADE로 지운다 -> 그 다음 회원을 지운다
	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	@BeforeEach
	void 준비() {
		정리(); // 실행 순서 꼬임을 막으려고 준비 안에서 먼저 비운다
		글쓴이 = TestLogin.사용자(userRepository, "writer");
		다른회원 = TestLogin.사용자(userRepository, "other");
		글쓴이쿠키 = TestLogin.쿠키(tokenService, 글쓴이);
		다른쿠키 = TestLogin.쿠키(tokenService, 다른회원);
		글 = postRepository.save(new Post("제목", "내용", 글쓴이, null, PostCategory.CHAT));
	}

	// ---------- 도우미 ----------

	private static String json(String content) {
		return "{\"content\":\"" + content + "\"}";
	}

	private MockHttpServletResponse 작성(Cookie cookie, Object postId, String body) throws Exception {
		var req = post("/posts/" + postId + "/comments").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private long 댓글만들기(Cookie cookie, String content) throws Exception {
		MockHttpServletResponse res = 작성(cookie, 글.getId(), json(content));
		assertThat(res.getStatus()).isEqualTo(201);
		return om.readTree(res.getContentAsString()).get("id").asLong();
	}

	private MockHttpServletResponse 수정(Cookie cookie, Object id, String body) throws Exception {
		var req = put("/comments/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 삭제(Cookie cookie, Object id) throws Exception {
		var req = delete("/comments/" + id).with(csrf());
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 목록(Object postId) throws Exception {
		return mockMvc.perform(get("/posts/" + postId + "/comments")).andReturn().getResponse();
	}

	private long 댓글수() {
		return jdbc.queryForObject("select count(*) from comments", Long.class);
	}

	// ============ 글 삭제 시 댓글도 함께 ============

	@Test
	void 댓글_달린_글을_삭제하면_204이고_댓글도_사라진다_500_아님() throws Exception {
		댓글만들기(다른쿠키, "댓글1");
		댓글만들기(글쓴이쿠키, "댓글2");
		assertThat(댓글수()).isEqualTo(2);

		MockHttpServletResponse res = mockMvc.perform(delete("/posts/" + 글.getId()).with(csrf()).cookie(글쓴이쿠키))
				.andReturn().getResponse();

		assertThat(res.getStatus()).isEqualTo(204);
		assertThat(댓글수()).isZero();
		assertThat(postRepository.count()).isZero();
	}

	// ============ POST 댓글 ============

	@Test
	void 로그인하고_댓글을_쓰면_201과_모든_응답_필드와_Location을_반환한다() throws Exception {
		MockHttpServletResponse res = 작성(다른쿠키, 글.getId(), json("첫 댓글"));

		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode body = om.readTree(res.getContentAsString());
		long id = body.get("id").asLong();
		assertThat(id).isPositive();
		assertThat(body.get("postId").asLong()).isEqualTo(글.getId());
		assertThat(body.get("content").asString()).isEqualTo("첫 댓글");
		assertThat(body.get("createdAt").asString()).isNotBlank();
		assertThat(body.get("author").get("id").asLong()).isEqualTo(다른회원.getId());
		assertThat(body.get("author").get("nickname").asString()).isEqualTo("other");
		assertThat(res.getHeader("Location")).isEqualTo("/comments/" + id);
		assertThat(댓글수()).isEqualTo(1);
	}

	@Test
	void 비로그인으로_댓글을_쓰면_401이고_저장되지_않는다() throws Exception {
		assertThat(작성(null, 글.getId(), json("x")).getStatus()).isEqualTo(401);
		assertThat(댓글수()).isZero();
	}

	@Test
	void CSRF_없이_댓글을_쓰면_403이다() throws Exception {
		int st = mockMvc.perform(post("/posts/" + 글.getId() + "/comments").cookie(다른쿠키)
				.contentType(MediaType.APPLICATION_JSON).content(json("x"))).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(403);
		assertThat(댓글수()).isZero();
	}

	@Test
	void 없는_글에_댓글을_쓰면_404다() throws Exception {
		assertThat(작성(다른쿠키, 999999999L, json("x")).getStatus()).isEqualTo(404);
		assertThat(댓글수()).isZero();
	}

	@Test
	void 공백만_있는_댓글은_400이다() throws Exception {
		assertThat(작성(다른쿠키, 글.getId(), json("   ")).getStatus()).isEqualTo(400);
		assertThat(댓글수()).isZero();
	}

	@Test
	void 내용이_없는_본문은_400이다() throws Exception {
		assertThat(작성(다른쿠키, 글.getId(), "{}").getStatus()).isEqualTo(400);
	}

	@Test
	void 천일자_댓글은_201이고_천한자_댓글은_400이다() throws Exception {
		assertThat(작성(다른쿠키, 글.getId(), json("a".repeat(1000))).getStatus()).isEqualTo(201);
		assertThat(작성(다른쿠키, 글.getId(), json("a".repeat(1001))).getStatus()).isEqualTo(400);
		assertThat(댓글수()).isEqualTo(1);
	}

	@Test
	void NUL_문자가_들어간_댓글은_400이다_500_아님() throws Exception {
		// JSON 이스케이프 \u0000 = NUL 문자
		assertThat(작성(다른쿠키, 글.getId(), "{\"content\":\"abc\\u0000def\"}").getStatus()).isEqualTo(400);
		assertThat(댓글수()).isZero();
	}

	// ============ GET 댓글 ============

	@Test
	void 비로그인도_목록을_조회하면_200이고_id_오름차순이다() throws Exception {
		long a = 댓글만들기(다른쿠키, "먼저");
		long b = 댓글만들기(글쓴이쿠키, "나중");
		long c = 댓글만들기(다른쿠키, "맨나중");

		MockHttpServletResponse res = 목록(글.getId());

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = om.readTree(res.getContentAsString());
		assertThat(arr.size()).isEqualTo(3);
		assertThat(arr.get(0).get("id").asLong()).isEqualTo(a);
		assertThat(arr.get(1).get("id").asLong()).isEqualTo(b);
		assertThat(arr.get(2).get("id").asLong()).isEqualTo(c);
		assertThat(arr.get(0).get("content").asString()).isEqualTo("먼저");
		assertThat(arr.get(0).get("postId").asLong()).isEqualTo(글.getId());
		assertThat(arr.get(0).get("author").get("nickname").asString()).isEqualTo("other");
	}

	@Test
	void 댓글이_없는_글의_목록은_빈_배열이다() throws Exception {
		MockHttpServletResponse res = 목록(글.getId());
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(om.readTree(res.getContentAsString()).isArray()).isTrue();
		assertThat(om.readTree(res.getContentAsString()).size()).isZero();
	}

	@Test
	void 다른_글의_댓글은_목록에_섞이지_않는다() throws Exception {
		댓글만들기(다른쿠키, "이 글 댓글");
		Post 다른글 = postRepository.save(new Post("다른", "내용", 글쓴이, null, PostCategory.CHAT));
		assertThat(om.readTree(목록(다른글.getId()).getContentAsString()).size()).isZero();
	}

	@Test
	void 없는_글의_목록은_404다() throws Exception {
		assertThat(목록(999999999L).getStatus()).isEqualTo(404);
	}

	@Test
	void postId가_숫자가_아니면_400이다() throws Exception {
		assertThat(목록("abc").getStatus()).isEqualTo(400);
	}

	// ============ PUT ============

	@Test
	void 본인_댓글을_수정하면_200이고_내용이_바뀐다() throws Exception {
		long id = 댓글만들기(다른쿠키, "원래");

		MockHttpServletResponse res = 수정(다른쿠키, id, json("수정됨"));

		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode body = om.readTree(res.getContentAsString());
		assertThat(body.get("id").asLong()).isEqualTo(id);
		assertThat(body.get("content").asString()).isEqualTo("수정됨");
		assertThat(jdbc.queryForObject("select content from comments where id = ?", String.class, id)).isEqualTo("수정됨");
	}

	@Test
	void 남의_댓글을_수정하면_403이고_내용은_그대로다() throws Exception {
		long id = 댓글만들기(다른쿠키, "원래");
		assertThat(수정(글쓴이쿠키, id, json("해킹")).getStatus()).isEqualTo(403);
		assertThat(jdbc.queryForObject("select content from comments where id = ?", String.class, id)).isEqualTo("원래");
	}

	@Test
	void 없는_댓글을_수정하면_404다() throws Exception {
		assertThat(수정(다른쿠키, 999999999L, json("x")).getStatus()).isEqualTo(404);
	}

	@Test
	void 수정_본문이_잘못되면_400이다() throws Exception {
		long id = 댓글만들기(다른쿠키, "원래");
		assertThat(수정(다른쿠키, id, json("  ")).getStatus()).isEqualTo(400);
		assertThat(수정(다른쿠키, id, json("a".repeat(1001))).getStatus()).isEqualTo(400);
		assertThat(수정(다른쿠키, id, "{\"content\":\"a\\u0000b\"}").getStatus()).isEqualTo(400);
		assertThat(jdbc.queryForObject("select content from comments where id = ?", String.class, id)).isEqualTo("원래");
	}

	@Test
	void 비로그인으로_수정하면_401이다() throws Exception {
		long id = 댓글만들기(다른쿠키, "원래");
		assertThat(수정(null, id, json("x")).getStatus()).isEqualTo(401);
	}

	// ============ DELETE ============

	@Test
	void 본인_댓글을_삭제하면_204이고_같은_id_두_번째는_404다() throws Exception {
		long id = 댓글만들기(다른쿠키, "지울 것");

		assertThat(삭제(다른쿠키, id).getStatus()).isEqualTo(204);
		assertThat(댓글수()).isZero();
		assertThat(삭제(다른쿠키, id).getStatus()).isEqualTo(404);
	}

	@Test
	void 남의_댓글을_삭제하면_403이고_댓글은_남는다() throws Exception {
		long id = 댓글만들기(다른쿠키, "남의 것");
		assertThat(삭제(글쓴이쿠키, id).getStatus()).isEqualTo(403);
		assertThat(댓글수()).isEqualTo(1);
	}

	@Test
	void 비로그인으로_삭제하면_401이다() throws Exception {
		long id = 댓글만들기(다른쿠키, "x");
		assertThat(삭제(null, id).getStatus()).isEqualTo(401);
		assertThat(댓글수()).isEqualTo(1);
	}

	// ============ 명세 공백: CSRF ============

	@Test
	void CSRF_없이_댓글을_수정하면_403이고_내용은_그대로다() throws Exception {
		long id = 댓글만들기(다른쿠키, "원래");
		int st = mockMvc.perform(put("/comments/" + id).cookie(다른쿠키).contentType(MediaType.APPLICATION_JSON)
				.content(json("바꿈"))).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(403);
		assertThat(jdbc.queryForObject("select content from comments where id = ?", String.class, id)).isEqualTo("원래");
	}

	@Test
	void CSRF_없이_댓글을_삭제하면_403이고_댓글은_남는다() throws Exception {
		long id = 댓글만들기(다른쿠키, "남을 것");
		int st = mockMvc.perform(delete("/comments/" + id).cookie(다른쿠키)).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(403);
		assertThat(댓글수()).isEqualTo(1);
	}

	// ============ 유령 토큰 / 경쟁 조건 (FK 위반을 제약 이름으로 구분하는 분기) ============

	@Autowired
	JwtEncoder jwtEncoder;
	@Autowired
	PlatformTransactionManager txManager;

	@Test
	void 토큰은_유효하지만_회원_행이_없으면_댓글_작성은_401이고_저장되지_않는다() throws Exception {
		assertThat(작성(TestLogin.행없는_회원_토큰(jwtEncoder), 글.getId(), json("x")).getStatus()).isEqualTo(401);
		assertThat(댓글수()).isZero();
	}

	// 회원 행이 잠긴 채 댓글 INSERT가 대기하다가 회원이 삭제 커밋되면 fk_comments_author 위반 -> 401(500 아님)
	@Test
	void 탈퇴가_진행되는_동안_댓글을_쓰면_락에서_기다리다가_401이고_댓글이_안_생긴다() throws Exception {
		Long uid = 다른회원.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from users where id = ? for update", Long.class, uid),
				() -> 작성(다른쿠키, 글.getId(), json("경쟁")).getStatus(),
				() -> jdbc.update("delete from users where id = ?", uid));

		assertThat(status).isEqualTo(401);
		assertThat(댓글수()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Long.class, uid)).isZero();
	}

	// 글 행의 삭제가 커밋 전일 때 댓글 INSERT가 대기하다가 글이 사라지면 fk_comments_post 위반 -> 404(500 아님)
	@Test
	void 글_삭제가_진행되는_동안_댓글을_쓰면_락에서_기다리다가_404이고_댓글이_안_생긴다() throws Exception {
		Long pid = 글.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from posts where id = ?", pid), // 커밋 전 삭제 = 글 행 잠금
				() -> 작성(다른쿠키, pid, json("경쟁")).getStatus(),
				() -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(댓글수()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from posts where id = ?", Long.class, pid)).isZero();
	}

	// ============ 회원 탈퇴 ============

	@Test
	void 댓글_쓴_회원이_탈퇴하면_댓글은_남고_author가_null이며_다른_회원의_수정_삭제는_403이다() throws Exception {
		// 탈퇴는 비밀번호 확인이 필요하므로 실제 가입·로그인을 거친 회원을 쓴다
		String pw = "password123";
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"leaver\",\"nickname\":\"탈퇴자\",\"password\":\"" + pw + "\"}"))
				.andReturn();
		MockHttpServletResponse login = mockMvc.perform(post("/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"leaver\",\"password\":\"" + pw + "\"}"))
				.andReturn().getResponse();
		assertThat(login.getStatus()).isEqualTo(200);
		Cookie leaverCookie = new Cookie("access_token", login.getCookie("access_token").getValue());

		long id = 댓글만들기(leaverCookie, "탈퇴자의 댓글");
		long otherId = 댓글만들기(다른쿠키, "남은 사람 댓글");

		int withdraw = mockMvc.perform(delete("/users/me").with(csrf()).cookie(leaverCookie)
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + pw + "\"}"))
				.andReturn().getResponse().getStatus();
		assertThat(withdraw).isEqualTo(204);

		// 댓글은 남고 author만 null
		assertThat(댓글수()).isEqualTo(2);
		JsonNode arr = om.readTree(목록(글.getId()).getContentAsString());
		assertThat(arr.size()).isEqualTo(2);
		JsonNode left = arr.get(0).get("id").asLong() == id ? arr.get(0) : arr.get(1);
		assertThat(left.get("content").asString()).isEqualTo("탈퇴자의 댓글");
		assertThat(left.get("author") == null || left.get("author").isNull()).isTrue();
		JsonNode kept = arr.get(0).get("id").asLong() == otherId ? arr.get(0) : arr.get(1);
		assertThat(kept.get("author").get("nickname").asString()).isEqualTo("other");

		// 작성자 없는 댓글은 누구도 수정·삭제할 수 없다 (403)
		assertThat(수정(다른쿠키, id, json("가로채기")).getStatus()).isEqualTo(403);
		assertThat(삭제(다른쿠키, id).getStatus()).isEqualTo(403);
		assertThat(댓글수()).isEqualTo(2);
	}
}
