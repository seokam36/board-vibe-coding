package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S5-d 테스트: 회원 탈퇴(DELETE /users/me)의 완료 조건을 검사한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class UserWithdrawTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PostRepository postRepository;
	@Autowired
	StringRedisTemplate redis;
	@Autowired
	JwtEncoder jwtEncoder;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	PlatformTransactionManager txManager;

	private final ObjectMapper om = new ObjectMapper();

	private static final String PW = "password123";

	// 테스트끼리 섞이지 않게 DB와 Redis를 앞뒤로 비운다(글을 회원보다 먼저: FK)
	@BeforeEach
	@AfterEach
	void 정리() {
		jdbc.execute("drop table if exists withdraw_block_test");
		postRepository.deleteAll();
		userRepository.deleteAll();
		for (String pattern : new String[] { "refresh:*", "user-refresh:*" }) {
			Set<String> keys = redis.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}

	// ---------- 도우미 ----------

	private void 가입(String username, String nickname) throws Exception {
		가입결과(username, nickname).andExpect(status().isCreated());
	}

	private ResultActions 가입결과(String username, String nickname) throws Exception {
		return mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"nickname\":\"" + nickname + "\",\"password\":\"" + PW
						+ "\"}"));
	}

	private MockHttpServletResponse 로그인(String username) throws Exception {
		return mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + PW + "\"}")).andReturn().getResponse();
	}

	private Cookie 액세스(MockHttpServletResponse login) {
		return new Cookie("access_token", login.getCookie("access_token").getValue());
	}

	private int 재발급_상태(String refresh) throws Exception {
		return mockMvc.perform(post("/auth/refresh").with(csrf()).cookie(new Cookie("refresh_token", refresh)))
				.andReturn().getResponse().getStatus();
	}

	private ResultActions 탈퇴(Cookie access, String bodyJson) throws Exception {
		return mockMvc.perform(delete("/users/me").with(csrf()).cookie(access).contentType(MediaType.APPLICATION_JSON)
				.content(bodyJson));
	}

	private ResultActions 탈퇴값(Cookie access, String password) throws Exception {
		return 탈퇴(access, "{\"password\":\"" + password + "\"}");
	}

	private Long id(String username) {
		return userRepository.findByUsername(username).orElseThrow().getId();
	}

	private boolean 회원있음(String username) {
		return userRepository.findByUsername(username).isPresent();
	}

	private Post 글(String username, String title) {
		return postRepository.save(new Post(title, "내용", userRepository.findByUsername(username).orElseThrow(), null, PostCategory.CHAT));
	}

	private Long 글_작성자_id(Long postId) {
		return jdbc.queryForObject("select author_id from posts where id = ?", Long.class, postId);
	}

	private Cookie 행없는_회원_토큰() {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("999999999").claim("username", "ghost").issuedAt(now)
				.expiresAt(now.plusSeconds(900)).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		return new Cookie("access_token", token);
	}

	// ============ 조건 1: 성공 응답 ============

	@Test
	void 조건1_맞는_비밀번호면_204_본문없음_두_쿠키_삭제_그리고_회원_행이_사라진다() throws Exception {
		가입("user01", "닉네임일");
		MockHttpServletResponse login = 로그인("user01");
		MockHttpServletResponse res = 탈퇴값(액세스(login), PW).andReturn().getResponse();

		assertThat(res.getStatus()).isEqualTo(204);
		assertThat(res.getContentAsString()).isEmpty();
		List<String> setCookies = res.getHeaders(HttpHeaders.SET_COOKIE);
		assertThat(setCookies).anyMatch(c -> c.startsWith("access_token=;") && c.contains("Max-Age=0"));
		assertThat(setCookies).anyMatch(c -> c.startsWith("refresh_token=;") && c.contains("Max-Age=0"));
		assertThat(회원있음("user01")).isFalse();
		assertThat(userRepository.count()).isZero();
	}

	// ============ 조건 2: 글은 남고 author만 null ============

	@Test
	void 조건2_탈퇴해도_글은_남고_상세와_목록에서_author만_null이며_남의_글은_그대로다() throws Exception {
		가입("user01", "닉네임일");
		가입("user02", "닉네임이");
		Post a1 = 글("user01", "탈퇴자 글1");
		Post a2 = 글("user01", "탈퇴자 글2");
		Post b1 = 글("user02", "남의 글");
		MockHttpServletResponse login = 로그인("user01");

		탈퇴값(액세스(login), PW).andExpect(status().isNoContent());

		assertThat(postRepository.count()).isEqualTo(3);
		mockMvc.perform(get("/posts/" + a1.getId())).andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("탈퇴자 글1")).andExpect(jsonPath("$.author").value((Object) null));
		mockMvc.perform(get("/posts/" + a2.getId())).andExpect(status().isOk())
				.andExpect(jsonPath("$.author").value((Object) null));
		mockMvc.perform(get("/posts/" + b1.getId())).andExpect(status().isOk())
				.andExpect(jsonPath("$.author.nickname").value("닉네임이"));

		String listBody = mockMvc.perform(get("/posts")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		JsonNode list = om.readTree(listBody);
		assertThat(list.size()).isEqualTo(3);
		for (JsonNode p : list) {
			long pid = p.get("id").asLong();
			if (pid == b1.getId()) {
				assertThat(p.get("author").isNull()).isFalse();
				assertThat(p.get("author").get("nickname").asString()).isEqualTo("닉네임이");
			} else {
				assertThat(p.get("author") == null || p.get("author").isNull()).isTrue();
			}
		}
	}

	// ============ 조건 3: refresh 토큰 ============

	@Test
	void 조건3_탈퇴_전_받은_refresh_토큰_둘은_401이고_다른_회원_토큰은_200() throws Exception {
		가입("user01", "닉네임일");
		가입("user02", "닉네임이");
		MockHttpServletResponse login1 = 로그인("user01");
		String r1 = login1.getCookie("refresh_token").getValue();
		String r2 = 로그인("user01").getCookie("refresh_token").getValue();
		String other = 로그인("user02").getCookie("refresh_token").getValue();

		탈퇴값(액세스(login1), PW).andExpect(status().isNoContent());

		assertThat(재발급_상태(r1)).isEqualTo(401);
		assertThat(재발급_상태(r2)).isEqualTo(401);
		assertThat(재발급_상태(other)).isEqualTo(200);
	}

	// ============ 조건 4 + 9: 옛 access 토큰 ============

	@Test
	void 조건4_9_탈퇴_뒤_옛_access_토큰으로_me_글쓰기_재탈퇴는_모두_401이고_500이_아니다() throws Exception {
		가입("user01", "닉네임일");
		Cookie access = 액세스(로그인("user01"));
		탈퇴값(access, PW).andExpect(status().isNoContent());

		mockMvc.perform(get("/auth/me").cookie(access)).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/posts").with(csrf()).cookie(access).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}")).andExpect(status().isUnauthorized());
		탈퇴값(access, PW).andExpect(status().isUnauthorized());
		assertThat(postRepository.count()).isZero();
	}

	// 경쟁: 탈퇴와 글쓰기를 동시에 여러 번 보내도 탈퇴는 항상 204, 글쓰기는 201/401(500 없음).
	// 회원 행 잠금 덕분에 탈퇴 뒤에 author가 남은 글(고아)이 없어야 하고, 201 수 = 실제로 생긴 글 수여야 한다.
	@Test
	void 조건9_탈퇴와_글쓰기가_동시에_일어나도_탈퇴는_항상_204이고_고아_글_없이_201수와_글수가_일치한다() throws Exception {
		for (int round = 0; round < 20; round++) {
			정리();
			가입("user01", "닉네임일");
			Cookie access = 액세스(로그인("user01"));
			int writers = 6;
			ExecutorService pool = Executors.newFixedThreadPool(writers + 1);
			try {
				CountDownLatch ready = new CountDownLatch(writers + 1);
				CountDownLatch go = new CountDownLatch(1);
				List<Future<Integer>> writes = new ArrayList<>();
				for (int i = 0; i < writers; i++) {
					writes.add(pool.submit(() -> {
						ready.countDown();
						go.await();
						return mockMvc.perform(post("/posts").with(csrf()).cookie(access)
								.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}"))
								.andReturn().getResponse().getStatus();
					}));
				}
				Future<Integer> withdraw = pool.submit(() -> {
					ready.countDown();
					go.await();
					return 탈퇴값(access, PW).andReturn().getResponse().getStatus();
				});
				ready.await();
				go.countDown();
				assertThat(withdraw.get()).isEqualTo(204);
				int created = 0;
				for (Future<Integer> f : writes) {
					int st = f.get();
					assertThat(st).isIn(201, 401);
					if (st == 201) created++;
				}
				assertThat(회원있음("user01")).isFalse();
				assertThat(jdbc.queryForObject("select count(*) from posts where author_id is not null", Long.class))
						.isZero();
				assertThat(jdbc.queryForObject("select count(*) from posts", Long.class)).isEqualTo((long) created);
			} finally {
				pool.shutdown();
			}
		}
	}

	// 결정적 재현 A: 글 INSERT 트랜잭션이 커밋 전일 때 탈퇴는 회원 행 잠금에서 기다렸다가, 커밋된 그 글까지 author null로 비운다
	@Test
	void 조건9_글_INSERT가_커밋되기_전에는_탈퇴가_기다리다가_그_글까지_author를_비우고_204다() throws Exception {
		가입("user01", "닉네임일");
		Cookie access = 액세스(로그인("user01"));
		Long uid = id("user01");
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				// V2 이후 posts.category는 NOT NULL이라 직접 넣는 SQL에도 말머리를 같이 넣는다
				jdbc.update("insert into posts(title, content, created_at, author_id, category) values ('잠금', '내용', now(), ?, 'CHAT')", uid);
				Future<Integer> withdraw = pool.submit(() -> 탈퇴값(access, PW).andReturn().getResponse().getStatus());
				try {
					락대기_기다리기();
					// 글 INSERT(회원 행에 KEY SHARE 잠금)가 안 끝났으니 탈퇴는 아직 못 끝났어야 한다
					assertThat(withdraw.isDone()).isFalse();
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				// 여기서 콜백이 끝나면 커밋된다
				pending = withdraw;
			});
			assertThat(pending.get()).isEqualTo(204);
		} finally {
			pool.shutdown();
		}
		assertThat(회원있음("user01")).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from posts", Long.class)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("select count(*) from posts where author_id is not null", Long.class)).isZero();
	}

	// 결정적 재현 B: 탈퇴 쪽이 회원 행을 잠근 상태에서 글쓰기 요청이 INSERT를 시도하면 기다리다가, 회원이 삭제 커밋되면 FK 위반 -> 401(500 아님)
	@Test
	void 조건9_회원_행이_잠긴_상태에서_글쓰기가_기다리다_회원이_삭제되면_401이고_글이_안_생긴다() throws Exception {
		가입("user01", "닉네임일");
		Cookie access = 액세스(로그인("user01"));
		Long uid = id("user01");
		ExecutorService pool = Executors.newSingleThreadExecutor();
		Future<Integer> write = null;
		try {
			final Future<Integer>[] holder = new Future[1];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				// 탈퇴 트랜잭션이 하는 것처럼 회원 행을 FOR UPDATE로 잠근다
				jdbc.queryForList("select id from users where id = ? for update", Long.class, uid);
				holder[0] = pool.submit(() -> mockMvc.perform(post("/posts").with(csrf()).cookie(access)
						.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}"))
						.andReturn().getResponse().getStatus());
				try {
					락대기_기다리기();
					assertThat(holder[0].isDone()).isFalse(); // INSERT가 잠금 때문에 기다리는 중
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				jdbc.update("delete from users where id = ?", uid); // 커밋되면 기다리던 INSERT는 FK 위반
			});
			write = holder[0];
			assertThat(write.get()).isEqualTo(401);
		} finally {
			pool.shutdown();
		}
		assertThat(jdbc.queryForObject("select count(*) from posts", Long.class)).isZero();
	}

	// 다른 커넥션이 락 때문에 기다리는 중(pg_stat_activity wait_event_type='Lock')이 될 때까지 최대 5초 폴링한다.
	// 고정 sleep보다 빠르고, 기다림이 실제로 생겼음을 DB가 직접 증명한다.
	private void 락대기_기다리기() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			Long n = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
					+ "and wait_event_type = 'Lock' and pid <> pg_backend_pid()", Long.class);
			if (n != null && n > 0) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("5초 안에 락 대기가 생기지 않았다");
	}

	// 글 수정 중 탈퇴: 수정 UPDATE가 author_id를 되돌려 쓰려다 FK 위반(500)이 나던 문제(updatable=false로 해결)를 재현한다.
	// 메인 트랜잭션이 글 행을 author null로 잠가 둔 채 수정 요청을 기다리게 하고, 회원을 지운 뒤 커밋한다.
	@Test
	void 글_수정_요청이_글_행_잠금에서_기다리는_동안_탈퇴가_커밋돼도_500이_아니고_작성자는_null이다() throws Exception {
		가입("user01", "닉네임일");
		Post p = 글("user01", "원래 제목");
		Cookie access = 액세스(로그인("user01"));
		Long uid = id("user01");
		ExecutorService pool = Executors.newSingleThreadExecutor();
		Future<Integer> put;
		try {
			final Future<Integer>[] holder = new Future[1];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				// 탈퇴 트랜잭션이 하는 것처럼 글의 작성자를 비운다(이 글 행이 잠긴다)
				jdbc.update("update posts set author_id = null where id = ?", p.getId());
				holder[0] = pool.submit(() -> mockMvc.perform(put("/posts/" + p.getId()).with(csrf()).cookie(access)
						.contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"새 제목\",\"content\":\"새 내용\",\"category\":\"QUESTION\"}"))
						.andReturn().getResponse().getStatus());
				try {
					락대기_기다리기();
					assertThat(holder[0].isDone()).isFalse();
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				jdbc.update("delete from users where id = ?", uid);
			});
			put = holder[0];
			int st = put.get();
			// 수정은 탈퇴와 겹쳐도 500이 아니다 (author_id를 다시 쓰지 않아 FK 검사가 안 돈다)
			assertThat(st).isLessThan(500);
		} finally {
			pool.shutdown();
		}
		assertThat(글_작성자_id(p.getId())).isNull();
		assertThat(회원있음("user01")).isFalse();
	}

	// updatable=false가 정상 수정에 영향이 없는지: 내 글을 수정하면 제목은 바뀌고 작성자는 그대로
	@Test
	void 일반_글_수정은_제목이_바뀌고_작성자는_그대로다() throws Exception {
		가입("user01", "닉네임일");
		Post p = 글("user01", "원래 제목");
		Cookie access = 액세스(로그인("user01"));
		Long uid = id("user01");
		mockMvc.perform(put("/posts/" + p.getId()).with(csrf()).cookie(access).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"새 제목\",\"content\":\"새 내용\",\"category\":\"QUESTION\"}")).andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("새 제목")).andExpect(jsonPath("$.author.nickname").value("닉네임일"));
		assertThat(글_작성자_id(p.getId())).isEqualTo(uid);
	}

	private Future<Integer> pending; // 결정적 재현 A에서 콜백 밖으로 결과를 꺼내는 용도

	// ============ MANDATORY: 트랜잭션 밖 직접 호출 금지 ============

	@Test
	void 조건8_트랜잭션_밖에서_deleteUserById_lockById_clearAuthor를_직접_부르면_예외가_나고_데이터는_그대로다() throws Exception {
		가입("user01", "닉네임일");
		Post p = 글("user01", "내 글");
		Long uid = id("user01");

		assertThatThrownBy(() -> userRepository.deleteUserById(uid)).isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> userRepository.lockById(uid)).isInstanceOf(IllegalTransactionStateException.class);
		assertThatThrownBy(() -> postRepository.clearAuthor(uid)).isInstanceOf(IllegalTransactionStateException.class);

		assertThat(회원있음("user01")).isTrue();
		assertThat(글_작성자_id(p.getId())).isEqualTo(uid);
	}

	// 참고: PostService의 "FK 외 무결성 위반은 401로 바꾸지 않는다"는 생략했다.
	// 글 INSERT에서 FK 외에 걸릴 제약은 NOT NULL/길이뿐인데 둘 다 요청 DTO 검증(@Valid)이 먼저 막아서 HTTP로는 만들 수 없고,
	// 프로덕션 코드(PostService/Repository)를 목으로 바꾸는 것은 이 테스트 범위 밖이다.

	// ============ 조건 5: 재가입 ============

	@Test
	void 조건5_탈퇴_뒤_같은_아이디와_닉네임으로_재가입_201이고_새_회원은_옛_글을_수정삭제할_수_없다_403() throws Exception {
		가입("user01", "닉네임일");
		Post old = 글("user01", "옛 글");
		탈퇴값(액세스(로그인("user01")), PW).andExpect(status().isNoContent());

		가입결과("user01", "닉네임일").andExpect(status().isCreated());
		Cookie fresh = 액세스(로그인("user01"));

		mockMvc.perform(put("/posts/" + old.getId()).with(csrf()).cookie(fresh).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"새제목\",\"content\":\"새내용\",\"category\":\"QUESTION\"}")).andExpect(status().isForbidden());
		mockMvc.perform(delete("/posts/" + old.getId()).with(csrf()).cookie(fresh)).andExpect(status().isForbidden());
		assertThat(postRepository.findById(old.getId()).orElseThrow().getTitle()).isEqualTo("옛 글");
		assertThat(글_작성자_id(old.getId())).isNull();
	}

	// ============ 조건 6: 실패 시 그대로 ============

	@Test
	void 조건6_비밀번호가_틀리면_400이고_회원_글_작성자_refresh_토큰은_그대로다() throws Exception {
		가입("user01", "닉네임일");
		Post p = 글("user01", "내 글");
		MockHttpServletResponse login = 로그인("user01");
		String refresh = login.getCookie("refresh_token").getValue();
		Long uid = id("user01");

		MockHttpServletResponse res = 탈퇴값(액세스(login), "wrongPassw0rd").andReturn().getResponse();

		assertThat(res.getStatus()).isEqualTo(400);
		assertThat(res.getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
		assertThat(회원있음("user01")).isTrue();
		assertThat(글_작성자_id(p.getId())).isEqualTo(uid);
		assertThat(재발급_상태(refresh)).isEqualTo(200);
	}

	@ParameterizedTest
	@ValueSource(strings = { "{}", "{\"password\":null}", "{\"password\":\"\"}", "{\"password\":\"   \"}", "" })
	void 조건6_빈_값이나_필드_없음이면_400이고_그대로다(String body) throws Exception {
		가입("user01", "닉네임일");
		Post p = 글("user01", "내 글");
		Cookie access = 액세스(로그인("user01"));
		Long uid = id("user01");

		탈퇴(access, body).andExpect(status().isBadRequest());

		assertThat(회원있음("user01")).isTrue();
		assertThat(글_작성자_id(p.getId())).isEqualTo(uid);
	}

	@ParameterizedTest
	@ValueSource(strings = { "a65", "가25", "가24a" })
	void 조건6_너무_긴_비밀번호_65자_75바이트_73바이트는_400이고_그대로다(String kind) throws Exception {
		String tooLong = switch (kind) {
			case "a65" -> "a".repeat(65);
			case "가25" -> "가".repeat(25);
			default -> "가".repeat(24) + "a"; // 73바이트
		};
		가입("user01", "닉네임일");
		Post p = 글("user01", "내 글");
		Cookie access = 액세스(로그인("user01"));
		Long uid = id("user01");

		탈퇴값(access, tooLong).andExpect(status().isBadRequest());

		assertThat(회원있음("user01")).isTrue();
		assertThat(글_작성자_id(p.getId())).isEqualTo(uid);
	}

	// ============ 조건 7: 인증/CSRF ============

	@Test
	void 조건7_로그인_쿠키가_없으면_401() throws Exception {
		mockMvc.perform(delete("/users/me").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"" + PW + "\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void 조건7_CSRF_헤더가_없으면_403이고_회원이_그대로다() throws Exception {
		가입("user01", "닉네임일");
		Cookie access = 액세스(로그인("user01"));
		mockMvc.perform(delete("/users/me").cookie(access).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"" + PW + "\"}")).andExpect(status().isForbidden());
		assertThat(회원있음("user01")).isTrue();
	}

	@Test
	void 조건7_토큰은_유효하지만_회원_행이_없으면_401() throws Exception {
		탈퇴값(행없는_회원_토큰(), PW).andExpect(status().isUnauthorized());
	}

	// ============ 조건 8: 롤백 ============

	// 회원 삭제를 일부러 실패시키기 위해, users를 참조하는 임시 테이블 행을 만든다(FK 위반 -> DELETE 실패).
	// 글 author null 처리와 회원 삭제가 한 트랜잭션이면 글의 author도 원래대로 남아야 한다.
	@Test
	void 조건8_회원_삭제가_실패하면_글_작성자_null_처리도_롤백되어_글_작성자가_그대로다() throws Exception {
		가입("user01", "닉네임일");
		Post p = 글("user01", "내 글");
		Cookie access = 액세스(로그인("user01"));
		Long uid = id("user01");
		jdbc.execute("create table withdraw_block_test (user_id bigint references users(id))");
		jdbc.update("insert into withdraw_block_test(user_id) values (?)", uid);

		int status;
		try {
			status = 탈퇴값(access, PW).andReturn().getResponse().getStatus();
		} catch (Exception e) {
			status = 500; // 처리 안 된 예외는 MockMvc가 그대로 던진다 = 서버 오류
		}

		assertThat(status).isGreaterThanOrEqualTo(500);
		assertThat(회원있음("user01")).isTrue();
		assertThat(글_작성자_id(p.getId())).isEqualTo(uid);
	}
}
