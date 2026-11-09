package com.example.demo.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

// S4-d "글과 작성자 연결 + 권한" 완료 조건 검사
// (로그인한 사람만 글을 쓰고, 내 글만 고치고 지울 수 있는지 확인한다)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PostAuthorizationTests {

	private static final String VALID = "{\"title\":\"새제목\",\"content\":\"새본문\",\"schoolId\":1,\"category\":\"CHAT\"}";

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PostRepository postRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	org.springframework.jdbc.core.JdbcTemplate jdbc;
	@Autowired
	TokenService tokenService;
	@Autowired
	JwtEncoder jwtEncoder;
	@Autowired
	ObjectMapper objectMapper;
	@Autowired
	EntityManagerFactory entityManagerFactory;

	User userA;
	User userB;
	Cookie cookieA;
	Cookie cookieB;

	@BeforeEach
	void 준비() {
		정리();
		userA = TestLogin.인증된_사용자(userRepository, jdbc, "userA", "경희대학교");
		userB = TestLogin.인증된_사용자(userRepository, jdbc, "userB", "경희대학교");
		cookieA = TestLogin.쿠키(tokenService, userA);
		cookieB = TestLogin.쿠키(tokenService, userB);
	}

	@AfterEach
	void 정리() {
		// 글이 사용자를 참조(외래키)하므로 글부터 지운다
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private JsonNode 본문(MvcResult r) throws Exception {
		return objectMapper.readTree(r.getResponse().getContentAsString());
	}

	private Post 글저장(String title, String content, User author) {
		return postRepository.save(new Post(title, content, author, null, PostCategory.CHAT));
	}

	private Cookie 토큰쿠키(Long userId, Instant iat, Instant exp) {
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(String.valueOf(userId)).claim("username", "x")
				.issuedAt(iat).expiresAt(exp).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new Cookie("access_token", token);
	}

	private Cookie 만료쿠키() {
		return 토큰쿠키(userA.getId(), Instant.now().minusSeconds(3600), Instant.now().minusSeconds(2700));
	}

	private Cookie 변조쿠키() {
		return new Cookie("access_token", cookieA.getValue() + "x");
	}

	// 쓰기 요청(POST/PUT/DELETE)을 만든다. cookie가 null이면 쿠키 없음, withCsrf로 CSRF 헤더 여부 결정
	private int 요청(String method, String path, String json, Cookie cookie, boolean withCsrf) throws Exception {
		MockHttpServletRequestBuilder b = switch (method) {
			case "POST" -> post(path);
			case "PUT" -> put(path);
			default -> delete(path);
		};
		if (json != null) {
			b.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		if (cookie != null) {
			b.cookie(cookie);
		}
		if (withCsrf) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse().getStatus();
	}

	private JsonNode 조회(long id) throws Exception {
		MvcResult r = mockMvc.perform(get("/posts/" + id)).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		return 본문(r);
	}

	// ---------- 조건 1: 작성자는 로그인 정보로만 정해진다 ----------

	@Test
	void 로그인한_사용자가_글을_쓰면_201과_작성자_정보가_응답에_들어간다() throws Exception {
		MvcResult r = mockMvc.perform(post("/posts").with(csrf()).cookie(cookieA).contentType(MediaType.APPLICATION_JSON)
				.content(VALID)).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(201);
		JsonNode body = 본문(r);
		assertThat(body.get("author").get("id").asLong()).isEqualTo(userA.getId());
		assertThat(body.get("author").get("nickname").asString()).isEqualTo("userA");
	}

	@Test
	void 본문에_author를_넣어도_무시되고_로그인한_사용자로_저장된다() throws Exception {
		String json = "{\"title\":\"t\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\",\"authorId\":" + userB.getId() + ",\"author\":{\"id\":"
				+ userB.getId() + ",\"username\":\"userB\"}}";
		MvcResult r = mockMvc.perform(post("/posts").with(csrf()).cookie(cookieA).contentType(MediaType.APPLICATION_JSON)
				.content(json)).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(201);
		long id = 본문(r).get("id").asLong();
		// DB에서 직접 확인: 작성자는 A여야 한다
		Post saved = postRepository.findById(id).orElseThrow();
		assertThat(saved.isWrittenBy(userA.getId())).isTrue();
		assertThat(saved.isWrittenBy(userB.getId())).isFalse();
	}

	// ---------- 조건 2: 로그인 안 됨(쿠키 없음/만료/변조) → 401, 데이터 불변 ----------

	@Test
	void 쿠키없음_만료_변조_상태의_쓰기요청은_401이고_DB는_변하지_않는다() throws Exception {
		Post p = 글저장("원래제목", "원래본문", userA);
		long before = postRepository.count();
		Cookie[] cookies = { null, 만료쿠키(), 변조쿠키() };
		// 실패 메시지에서 어느 경우인지 구분되도록 이름을 따로 붙인다 (만료와 변조를 같은 이름으로 쓰지 않는다)
		String[] names = { "쿠키없음", "만료쿠키", "변조쿠키" };
		for (int i = 0; i < cookies.length; i++) {
			Cookie c = cookies[i];
			String name = names[i];
			assertThat(요청("POST", "/posts", VALID, c, true)).as("POST " + name).isEqualTo(401);
			assertThat(요청("PUT", "/posts/" + p.getId(), VALID, c, true)).as("PUT " + name).isEqualTo(401);
			assertThat(요청("DELETE", "/posts/" + p.getId(), null, c, true)).as("DELETE " + name).isEqualTo(401);
		}
		assertThat(postRepository.count()).isEqualTo(before);
		Post after = postRepository.findById(p.getId()).orElseThrow();
		assertThat(after.getTitle()).isEqualTo("원래제목");
		assertThat(after.getContent()).isEqualTo("원래본문");
	}

	// 경로·메서드 빈틈 회귀 테스트.
	// publicEndpoints를 /posts/**나 메서드 없는 매처로 넓히면 쓰기 API가 조용히 공개되는 걸 잡기 위함
	@Test
	void 비로그인_상태에서_뒤슬래시_하위경로_HEAD_PATCH_요청도_401이고_글은_그대로다() throws Exception {
		Post p = 글저장("원래제목", "원래본문", userA);
		long before = postRepository.count();
		long id = p.getId();
		// 쿠키 없음 + CSRF 있음: 인증 검사에서만 걸리는지 보기 위해 CSRF는 넣는다
		assertThat(mockMvc.perform(post("/posts/").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(VALID))
				.andReturn().getResponse().getStatus()).as("POST /posts/").isEqualTo(401);
		assertThat(mockMvc.perform(put("/posts/" + id + "/").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(VALID)).andReturn().getResponse().getStatus()).as("PUT /posts/{id}/").isEqualTo(401);
		assertThat(mockMvc.perform(get("/posts/1/xxx")).andReturn().getResponse().getStatus()).as("GET /posts/1/xxx")
				.isEqualTo(401);
		assertThat(mockMvc.perform(head("/posts")).andReturn().getResponse().getStatus()).as("HEAD /posts")
				.isEqualTo(401);
		assertThat(mockMvc.perform(patch("/posts/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(VALID)).andReturn().getResponse().getStatus()).as("PATCH /posts/{id}").isEqualTo(401);
		// DB의 글이 바뀌지 않았다
		assertThat(postRepository.count()).isEqualTo(before);
		Post after = postRepository.findById(id).orElseThrow();
		assertThat(after.getTitle()).isEqualTo("원래제목");
		assertThat(after.getContent()).isEqualTo("원래본문");
	}

	// 검사 순서: 로그인 확인(401)이 없는 id(404)·잘못된 id(400)·잘못된 본문(400)보다 먼저다
	@Test
	void 비로그인이면_없는_id_숫자아닌_id_빈_title도_400_404가_아니라_401이다() throws Exception {
		assertThat(요청("PUT", "/posts/999999", VALID, null, true)).as("PUT 없는 id").isEqualTo(401);
		assertThat(요청("PUT", "/posts/abc", VALID, null, true)).as("PUT 숫자 아닌 id").isEqualTo(401);
		assertThat(요청("DELETE", "/posts/abc", null, null, true)).as("DELETE 숫자 아닌 id").isEqualTo(401);
		assertThat(요청("POST", "/posts", "{\"title\":\"\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}", null, true)).as("POST 빈 title")
				.isEqualTo(401);
	}

	// ---------- 조건 3: 작성자 본인은 수정·삭제 가능 ----------

	@Test
	void 작성자가_자기_글을_수정하면_200과_바뀐_내용을_반환한다() throws Exception {
		Post p = 글저장("원래제목", "원래본문", userA);
		MvcResult r = mockMvc.perform(put("/posts/" + p.getId()).with(csrf()).cookie(cookieA)
				.contentType(MediaType.APPLICATION_JSON).content(VALID)).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(본문(r).get("title").asString()).isEqualTo("새제목");
		assertThat(본문(r).get("content").asString()).isEqualTo("새본문");
		assertThat(조회(p.getId()).get("title").asString()).isEqualTo("새제목");
	}

	@Test
	void 작성자가_자기_글을_삭제하면_204이고_이후_조회는_404다() throws Exception {
		Post p = 글저장("t", "c", userA);
		assertThat(요청("DELETE", "/posts/" + p.getId(), null, cookieA, true)).isEqualTo(204);
		assertThat(mockMvc.perform(get("/posts/" + p.getId())).andReturn().getResponse().getStatus()).isEqualTo(404);
	}

	// ---------- 조건 4: 남의 글은 403 ----------

	@Test
	void 다른_사용자가_남의_글을_수정_삭제하면_403이고_글은_그대로다() throws Exception {
		Post p = 글저장("원래제목", "원래본문", userA);
		assertThat(요청("PUT", "/posts/" + p.getId(), VALID, cookieB, true)).isEqualTo(403);
		assertThat(요청("DELETE", "/posts/" + p.getId(), null, cookieB, true)).isEqualTo(403);
		JsonNode body = 조회(p.getId());
		assertThat(body.get("title").asString()).isEqualTo("원래제목");
		assertThat(body.get("content").asString()).isEqualTo("원래본문");
	}

	// ---------- 조건 5: 작성자 없는 옛 글은 아무도 수정·삭제 못 한다 ----------

	@Test
	void 작성자_없는_글은_로그인해도_수정_삭제가_403이고_글은_유지된다() throws Exception {
		Post p = 글저장("옛글", "옛본문", null);
		assertThat(요청("PUT", "/posts/" + p.getId(), VALID, cookieA, true)).isEqualTo(403);
		assertThat(요청("DELETE", "/posts/" + p.getId(), null, cookieA, true)).isEqualTo(403);
		assertThat(조회(p.getId()).get("title").asString()).isEqualTo("옛글");
	}

	// ---------- 조건 6: 404 / 400 우선순위 ----------

	@Test
	void 없는_글은_404_숫자아닌_id는_400이다() throws Exception {
		assertThat(요청("PUT", "/posts/999999", VALID, cookieA, true)).isEqualTo(404);
		assertThat(요청("DELETE", "/posts/999999", null, cookieA, true)).isEqualTo(404);
		assertThat(요청("PUT", "/posts/abc", VALID, cookieA, true)).isEqualTo(400);
		assertThat(요청("DELETE", "/posts/abc", null, cookieA, true)).isEqualTo(400);
	}

	@Test
	void 빈_title의_글쓰기와_본인글_수정은_400이다() throws Exception {
		String empty = "{\"title\":\"\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}";
		Post p = 글저장("원래제목", "원래본문", userA);
		assertThat(요청("POST", "/posts", empty, cookieA, true)).isEqualTo(400);
		assertThat(요청("PUT", "/posts/" + p.getId(), empty, cookieA, true)).isEqualTo(400);
		assertThat(조회(p.getId()).get("title").asString()).isEqualTo("원래제목");
	}

	@Test
	void 남의_글에_빈_title로_수정하면_403이_아니라_400이다() throws Exception {
		Post p = 글저장("원래제목", "원래본문", userA);
		assertThat(요청("PUT", "/posts/" + p.getId(), "{\"title\":\"\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}", cookieB, true))
				.isEqualTo(400);
	}

	// ---------- 조건 7: 읽기는 누구나, 작성자 정보 노출, 비밀번호 비노출 ----------

	@Test
	void 비로그인과_만료쿠키_상태에서도_조회는_200이고_작성자_정보가_맞다() throws Exception {
		Post withAuthor = 글저장("있음", "c", userA);
		Post noAuthor = 글저장("없음", "c", null);
		Cookie[] cookies = { null, 만료쿠키() };
		for (Cookie c : cookies) {
			MockHttpServletRequestBuilder list = get("/posts");
			MockHttpServletRequestBuilder one1 = get("/posts/" + withAuthor.getId());
			MockHttpServletRequestBuilder one2 = get("/posts/" + noAuthor.getId());
			if (c != null) {
				list.cookie(c);
				one1.cookie(c);
				one2.cookie(c);
			}
			MvcResult lr = mockMvc.perform(list).andReturn();
			MvcResult r1 = mockMvc.perform(one1).andReturn();
			MvcResult r2 = mockMvc.perform(one2).andReturn();
			assertThat(lr.getResponse().getStatus()).isEqualTo(200);
			assertThat(r1.getResponse().getStatus()).isEqualTo(200);
			assertThat(r2.getResponse().getStatus()).isEqualTo(200);

			JsonNode a = 본문(r1).get("author");
			assertThat(a.get("id").asLong()).isEqualTo(userA.getId());
			assertThat(a.get("nickname").asString()).isEqualTo("userA");
			assertThat(본문(r2).get("author").isNull()).isTrue();

			// 목록에서도 같은 규칙
			for (JsonNode item : 본문(lr)) {
				if (item.get("id").asLong() == withAuthor.getId()) {
					assertThat(item.get("author").get("nickname").asString()).isEqualTo("userA");
				} else {
					assertThat(item.get("author").isNull()).isTrue();
				}
			}
			// 어느 응답에도 password가 없다
			for (MvcResult m : new MvcResult[] { lr, r1, r2 }) {
				assertThat(m.getResponse().getContentAsString().toLowerCase()).doesNotContain("password");
			}
		}
	}

	// ---------- 조건 8: 토큰은 유효하지만 사용자가 DB에 없음 ----------

	@Test
	void 토큰_발급후_사용자가_삭제되면_글쓰기는_401이고_저장되지_않는다() throws Exception {
		User ghost = TestLogin.인증된_사용자(userRepository, jdbc, "ghost", "경희대학교");
		Cookie ghostCookie = TestLogin.쿠키(tokenService, ghost);
		userRepository.deleteById(ghost.getId());
		long before = postRepository.count();
		assertThat(요청("POST", "/posts", VALID, ghostCookie, true)).isEqualTo(401);
		assertThat(postRepository.count()).isEqualTo(before);
	}

	// ---------- 조건 9: CSRF 헤더 없으면 로그인 여부와 무관하게 403 ----------

	@Test
	void CSRF_없는_쓰기요청은_로그인_여부와_무관하게_403이다() throws Exception {
		Post p = 글저장("원래제목", "원래본문", userA);
		long before = postRepository.count();
		Cookie[] cookies = { null, cookieA };
		for (Cookie c : cookies) {
			assertThat(요청("POST", "/posts", VALID, c, false)).isEqualTo(403);
			assertThat(요청("PUT", "/posts/" + p.getId(), VALID, c, false)).isEqualTo(403);
			assertThat(요청("DELETE", "/posts/" + p.getId(), null, c, false)).isEqualTo(403);
		}
		assertThat(postRepository.count()).isEqualTo(before);
		assertThat(조회(p.getId()).get("title").asString()).isEqualTo("원래제목");
	}

	// ---------- 조건 10: 목록·상세 N+1 없음 ----------

	// 기본은 3번(글 1 + 댓글 수 1 + 공감 수 1). PostRepository의 findById override(@EntityGraph)를 지우면
	// LAZY라 작성자 조회가 한 번 더 나가 4번이 되는 걸 잡기 위함
	@Test
	void 작성자가_있는_글의_상세_조회는_쿼리가_3번만_나간다_글1_댓글수1_공감수1() throws Exception {
		Post p = 글저장("제목", "본문", userA);
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			MvcResult r = mockMvc.perform(get("/posts/" + p.getId())).andReturn();
			assertThat(r.getResponse().getStatus()).isEqualTo(200);
			assertThat(본문(r).get("author").get("nickname").asString()).isEqualTo("userA");
			assertThat(stats.getPrepareStatementCount()).as("상세 조회 쿼리 수(글 1 + 댓글 수 1 + 공감 수 1, 작성자 N+1 아님)").isEqualTo(3);
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	@Test
	void 작성자가_다른_글이_많아도_목록_조회_쿼리_수는_글_수에_비례하지_않는다() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			List<Long> counts = new ArrayList<>();
			// 글 1개(작성자 1명)일 때와 5개(작성자 5명)일 때 쿼리 수를 비교한다
			for (int n : new int[] { 1, 5 }) {
				postRepository.deleteAll();
				userRepository.deleteAll();
				for (int i = 0; i < n; i++) {
					User u = TestLogin.인증된_사용자(userRepository, jdbc, "writer" + i, "경희대학교");
					글저장("제목" + i, "본문", u);
				}
				stats.clear();
				MvcResult r = mockMvc.perform(get("/posts")).andReturn();
				assertThat(r.getResponse().getStatus()).isEqualTo(200);
				assertThat(본문(r).size()).isEqualTo(n);
				counts.add(stats.getPrepareStatementCount());
			}
			assertThat(counts.get(1)).as("글 5개 쿼리 수(%s)는 글 1개 쿼리 수(%s)와 같아야 한다", counts.get(1), counts.get(0))
					.isEqualTo(counts.get(0));
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}
}
