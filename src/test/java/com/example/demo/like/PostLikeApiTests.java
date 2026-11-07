package com.example.demo.like;

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
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S8-b 공감 API + 글 응답의 commentCount·likeCount 완료 조건 검사
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PostLikeApiTests {

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
	@Autowired
	EntityManagerFactory entityManagerFactory;

	User 글쓴이;
	User 회원A;
	User 회원B;
	Cookie 글쓴이쿠키;
	Cookie 쿠키A;
	Cookie 쿠키B;
	Post 글;

	// 글을 먼저 지우면 댓글·공감도 DB가 CASCADE로 지운다 -> 그 다음 회원을 지운다
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
	}

	// ---------- 도우미 ----------

	private MockHttpServletResponse 공감(Cookie cookie, Object postId) throws Exception {
		var req = post("/posts/" + postId + "/likes").with(csrf());
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 취소(Cookie cookie, Object postId) throws Exception {
		var req = delete("/posts/" + postId + "/likes").with(csrf());
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 내공감(Cookie cookie, Object postId) throws Exception {
		var req = get("/posts/" + postId + "/likes/me");
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private JsonNode 본문(MockHttpServletResponse res) throws Exception {
		return om.readTree(res.getContentAsString());
	}

	private long 공감행수() {
		return jdbc.queryForObject("select count(*) from post_likes", Long.class);
	}

	private void 댓글달기(Cookie cookie, Post target, String content) throws Exception {
		int st = mockMvc.perform(post("/posts/" + target.getId() + "/comments").with(csrf()).cookie(cookie)
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}"))
				.andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(201);
	}

	private JsonNode 글조회(Post target) throws Exception {
		MockHttpServletResponse res = mockMvc.perform(get("/posts/" + target.getId())).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		return 본문(res);
	}

	// ============ POST 공감 ============

	@Test
	void 로그인하고_공감하면_201과_likeCount_1_liked_true를_반환한다() throws Exception {
		MockHttpServletResponse res = 공감(쿠키A, 글.getId());

		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(본문(res).get("likeCount").asLong()).isEqualTo(1);
		assertThat(본문(res).get("liked").asBoolean()).isTrue();
		assertThat(공감행수()).isEqualTo(1);
	}

	@Test
	void 같은_사람이_또_공감하면_409이고_행은_1개다() throws Exception {
		assertThat(공감(쿠키A, 글.getId()).getStatus()).isEqualTo(201);
		assertThat(공감(쿠키A, 글.getId()).getStatus()).isEqualTo(409);
		assertThat(공감행수()).isEqualTo(1);
	}

	@Test
	void 없는_글에_공감하면_404다() throws Exception {
		assertThat(공감(쿠키A, 999999999L).getStatus()).isEqualTo(404);
		assertThat(공감행수()).isZero();
	}

	@Test
	void 비로그인으로_공감하면_401이다() throws Exception {
		assertThat(공감(null, 글.getId()).getStatus()).isEqualTo(401);
		assertThat(공감행수()).isZero();
	}

	@Test
	void CSRF_없이_공감하면_403이다() throws Exception {
		int st = mockMvc.perform(post("/posts/" + 글.getId() + "/likes").cookie(쿠키A)).andReturn().getResponse()
				.getStatus();
		assertThat(st).isEqualTo(403);
		assertThat(공감행수()).isZero();
	}

	// ============ DELETE 공감 ============

	@Test
	void 눌렀던_공감을_취소하면_200과_likeCount_0_liked_false다() throws Exception {
		공감(쿠키A, 글.getId());

		MockHttpServletResponse res = 취소(쿠키A, 글.getId());

		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(본문(res).get("likeCount").asLong()).isZero();
		assertThat(본문(res).get("liked").asBoolean()).isFalse();
		assertThat(공감행수()).isZero();
	}

	@Test
	void 안_눌렀는데_공감을_취소하면_404다() throws Exception {
		assertThat(취소(쿠키A, 글.getId()).getStatus()).isEqualTo(404);
	}

	@Test
	void 없는_글의_공감을_취소하면_404다() throws Exception {
		assertThat(취소(쿠키A, 999999999L).getStatus()).isEqualTo(404);
	}

	@Test
	void 비로그인으로_공감을_취소하면_401이다() throws Exception {
		공감(쿠키A, 글.getId());
		assertThat(취소(null, 글.getId()).getStatus()).isEqualTo(401);
		assertThat(공감행수()).isEqualTo(1);
	}

	// ============ GET /likes/me ============

	@Test
	void 공감_누르기_전에는_liked_false이고_누른_후에는_true다() throws Exception {
		MockHttpServletResponse before = 내공감(쿠키A, 글.getId());
		assertThat(before.getStatus()).isEqualTo(200);
		assertThat(본문(before).get("liked").asBoolean()).isFalse();

		공감(쿠키A, 글.getId());

		MockHttpServletResponse after = 내공감(쿠키A, 글.getId());
		assertThat(after.getStatus()).isEqualTo(200);
		assertThat(본문(after).get("liked").asBoolean()).isTrue();
		// 다른 회원 입장에서는 여전히 false (내 공감만 본다)
		assertThat(본문(내공감(쿠키B, 글.getId())).get("liked").asBoolean()).isFalse();
	}

	@Test
	void 비로그인으로_내_공감_여부를_조회하면_401이다() throws Exception {
		assertThat(내공감(null, 글.getId()).getStatus()).isEqualTo(401);
	}

	@Test
	void 없는_글의_내_공감_여부_조회는_404다() throws Exception {
		assertThat(내공감(쿠키A, 999999999L).getStatus()).isEqualTo(404);
	}

	// ============ 여러 회원 ============

	@Test
	void 다른_두_회원이_같은_글에_공감하면_likeCount가_2다() throws Exception {
		assertThat(본문(공감(쿠키A, 글.getId())).get("likeCount").asLong()).isEqualTo(1);
		MockHttpServletResponse res = 공감(쿠키B, 글.getId());

		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(본문(res).get("likeCount").asLong()).isEqualTo(2);
		assertThat(공감행수()).isEqualTo(2);
	}

	// ============ 동시 요청 ============

	@Test
	void 같은_회원이_같은_글에_동시에_10번_공감하면_201은_1번_나머지_409이고_행은_1개다() throws Exception {
		int n = 10;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				futures.add(pool.submit(() -> {
					ready.countDown();
					go.await(); // 모두 준비되면 한꺼번에 출발
					return 공감(쿠키A, 글.getId()).getStatus();
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
			assertThat(conflict).as("409 횟수").isEqualTo(n - 1);
			assertThat(공감행수()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	// ============ 글 응답의 commentCount·likeCount ============

	@Test
	void 글_상세는_댓글_2개_공감_3개면_2와_3을_반환한다() throws Exception {
		User 회원C = TestLogin.사용자(userRepository, "memberC");
		댓글달기(쿠키A, 글, "c1");
		댓글달기(쿠키B, 글, "c2");
		공감(쿠키A, 글.getId());
		공감(쿠키B, 글.getId());
		공감(TestLogin.쿠키(tokenService, 회원C), 글.getId());

		JsonNode body = 글조회(글);

		assertThat(body.get("commentCount").asLong()).isEqualTo(2);
		assertThat(body.get("likeCount").asLong()).isEqualTo(3);
	}

	@Test
	void 글_상세는_아무것도_없으면_0과_0을_반환한다() throws Exception {
		JsonNode body = 글조회(글);
		assertThat(body.get("commentCount").asLong()).isZero();
		assertThat(body.get("likeCount").asLong()).isZero();
	}

	@Test
	void 글_목록은_글마다_각자_맞는_댓글수_공감수를_반환한다() throws Exception {
		Post 둘째 = postRepository.save(new Post("둘째", "내용", 글쓴이, null, PostCategory.CHAT));
		Post 셋째 = postRepository.save(new Post("셋째", "내용", 글쓴이, null, PostCategory.CHAT));
		// 첫째: 댓글 2 / 공감 1, 둘째: 댓글 0 / 공감 2, 셋째: 아무것도 없음
		댓글달기(쿠키A, 글, "c1");
		댓글달기(쿠키B, 글, "c2");
		공감(쿠키A, 글.getId());
		공감(쿠키A, 둘째.getId());
		공감(쿠키B, 둘째.getId());

		MockHttpServletResponse res = mockMvc.perform(get("/posts")).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode arr = 본문(res);
		assertThat(arr.size()).isEqualTo(3);
		for (JsonNode p : arr) {
			long id = p.get("id").asLong();
			if (id == 글.getId()) {
				assertThat(p.get("commentCount").asLong()).isEqualTo(2);
				assertThat(p.get("likeCount").asLong()).isEqualTo(1);
			} else if (id == 둘째.getId()) {
				assertThat(p.get("commentCount").asLong()).isZero();
				assertThat(p.get("likeCount").asLong()).isEqualTo(2);
			} else if (id == 셋째.getId()) {
				assertThat(p.get("commentCount").asLong()).isZero();
				assertThat(p.get("likeCount").asLong()).isZero();
			} else {
				throw new AssertionError("예상 못한 글 id: " + id);
			}
		}
	}

	// 글마다 count 쿼리를 따로 보내면(N+1) 10개일 때 20번 이상 나간다. group by 2번이면 총 3번
	@Test
	void 글_10개일_때_글_목록_조회의_SQL은_3번_이하다() throws Exception {
		for (int i = 0; i < 9; i++) {
			postRepository.save(new Post("글" + i, "내용", 글쓴이, null, PostCategory.CHAT));
		}
		댓글달기(쿠키A, 글, "c1");
		공감(쿠키A, 글.getId());

		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			MockHttpServletResponse res = mockMvc.perform(get("/posts")).andReturn().getResponse();
			assertThat(res.getStatus()).isEqualTo(200);
			assertThat(본문(res).size()).isEqualTo(10);
			assertThat(stats.getPrepareStatementCount()).as("목록 조회 SQL 수").isLessThanOrEqualTo(3);
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	// ============ 탈퇴·글 삭제 연쇄 ============

	@Test
	void 공감한_회원이_탈퇴하면_likeCount가_1_줄고_공감_행이_사라진다() throws Exception {
		// 탈퇴는 비밀번호 확인이 필요하므로 실제 가입·로그인을 거친 회원을 쓴다
		String pw = "password123";
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"leaver\",\"nickname\":\"탈퇴자\",\"password\":\"" + pw + "\"}")).andReturn();
		MockHttpServletResponse login = mockMvc.perform(post("/auth/login").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"leaver\",\"password\":\"" + pw + "\"}"))
				.andReturn().getResponse();
		assertThat(login.getStatus()).isEqualTo(200);
		Cookie leaverCookie = new Cookie("access_token", login.getCookie("access_token").getValue());

		assertThat(공감(leaverCookie, 글.getId()).getStatus()).isEqualTo(201);
		assertThat(공감(쿠키A, 글.getId()).getStatus()).isEqualTo(201);
		assertThat(글조회(글).get("likeCount").asLong()).isEqualTo(2);

		int withdraw = mockMvc.perform(delete("/users/me").with(csrf()).cookie(leaverCookie)
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"" + pw + "\"}"))
				.andReturn().getResponse().getStatus();
		assertThat(withdraw).isEqualTo(204);

		assertThat(글조회(글).get("likeCount").asLong()).isEqualTo(1);
		assertThat(공감행수()).isEqualTo(1);
	}

	@Test
	void 댓글과_공감이_있는_글을_삭제하면_204이고_공감_행도_사라진다() throws Exception {
		댓글달기(쿠키A, 글, "c1");
		공감(쿠키A, 글.getId());
		공감(쿠키B, 글.getId());
		assertThat(공감행수()).isEqualTo(2);

		int st = mockMvc.perform(delete("/posts/" + 글.getId()).with(csrf()).cookie(글쓴이쿠키)).andReturn().getResponse()
				.getStatus();

		assertThat(st).isEqualTo(204);
		assertThat(공감행수()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from comments", Long.class)).isZero();
		assertThat(postRepository.count()).isZero();
	}
}
