package com.example.demo.report;

import static com.example.demo.report.ConversationReportFixture.본문;
import static com.example.demo.report.ConversationReportFixture.신고;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S14-f: POST /conversations/{id}/reports (쪽지 대화방 신고) 완료 조건 검사.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationReportApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired JwtEncoder jwtEncoder;

	User 판매자, 구매자, 제3자, 관리자;
	Cookie 판매자쿠키, 구매자쿠키, 제3자쿠키, 관리자쿠키;
	long 글id, 방id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "crseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "crbuyer", "연세대학교");
		제3자 = TestLogin.인증된_사용자(userRepository, jdbc, "crthird", "연세대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "cradmin");
		TestLogin.학교_인증_처리(jdbc, "cradmin", "연세대학교"); // 관리자도 인증은 해 둔다(403이 아니라 404를 보려고)
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		제3자쿠키 = TestLogin.쿠키(tokenService, 제3자);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		long[] ids = ConversationReportFixture.글과_방_만들기(mockMvc, om, 판매자쿠키, 구매자쿠키, "자전거");
		글id = ids[0];
		방id = ids[1];
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations"); // 쪽지·대화 신고는 CASCADE로 같이 지워진다
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private int 신고행수() {
		return jdbc.queryForObject("select count(*) from reports", Integer.class);
	}

	private JsonNode json(MockHttpServletResponse res) throws Exception {
		return om.readTree(res.getContentAsString());
	}

	// ============ 성공 ============

	@Test
	void 구매자가_신고하면_201이고_응답_대상은_CONVERSATION_방id_postId_null이다() throws Exception {
		MockHttpServletResponse res = 신고(mockMvc, 구매자쿠키, 방id, "{\"reason\":\"ABUSE\",\"detail\":\"욕을 해요\"}");

		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode b = json(res);
		assertThat(b.get("target").get("type").asString()).isEqualTo("CONVERSATION");
		assertThat(b.get("target").get("id").asLong()).isEqualTo(방id);
		assertThat(b.get("target").get("postId").isNull()).isTrue();
		assertThat(b.get("reason").get("code").asString()).isEqualTo("ABUSE");
		assertThat(b.get("reason").get("name").asString()).isEqualTo("욕설·비방");
		assertThat(b.get("detail").asString()).isEqualTo("욕을 해요");
		assertThat(b.get("status").asString()).isEqualTo("PENDING");
		// DB: 대화 칸만 채워지고 신고자는 구매자
		assertThat(jdbc.queryForMap("select conversation_id, post_id, comment_id, market_item_id, reporter_id from reports"))
				.containsEntry("conversation_id", 방id).containsEntry("reporter_id", 구매자.getId())
				.containsEntry("post_id", null).containsEntry("comment_id", null).containsEntry("market_item_id", null);
	}

	@Test
	void 판매자도_신고할_수_있다() throws Exception {
		assertThat(신고(mockMvc, 판매자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(jdbc.queryForObject("select reporter_id from reports", Long.class)).isEqualTo(판매자.getId());
	}

	@Test
	void 구매자와_판매자가_각각_신고하면_둘_다_201이고_행은_2개다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(신고(mockMvc, 판매자쿠키, 방id, 본문("OTHER")).getStatus()).isEqualTo(201);
		assertThat(신고행수()).isEqualTo(2);
	}

	@Test
	void detail은_선택이고_공백뿐이면_null로_저장되며_300자는_허용된다() throws Exception {
		MockHttpServletResponse a = 신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM"));
		assertThat(a.getStatus()).isEqualTo(201);
		assertThat(json(a).get("detail").isNull()).isTrue();

		MockHttpServletResponse b = 신고(mockMvc, 판매자쿠키, 방id, "{\"reason\":\"SPAM\",\"detail\":\"   \"}");
		assertThat(b.getStatus()).isEqualTo(201);
		assertThat(json(b).get("detail").isNull()).isTrue();

		jdbc.update("delete from reports");
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "{\"reason\":\"SPAM\",\"detail\":\"" + "가".repeat(300) + "\"}").getStatus())
				.isEqualTo(201);
	}

	@Test
	void 사유_다섯_개가_모두_받아진다() throws Exception {
		for (String reason : new String[] { "SPAM", "ABUSE", "SEXUAL", "PRIVACY", "OTHER" }) {
			jdbc.update("delete from reports");
			assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문(reason)).getStatus()).as(reason).isEqualTo(201);
		}
	}

	@Test
	void 상대가_탈퇴한_방도_신고할_수_있다() throws Exception {
		// 탈퇴하면 DB가 방의 판매자 칸을 NULL로 비운다(ON DELETE SET NULL)
		jdbc.update("update conversations set seller_id = null where id = ?", 방id);

		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("ABUSE")).getStatus()).isEqualTo(201);
		assertThat(신고행수()).isEqualTo(1);
	}

	@Test
	void 글이_삭제된_방도_신고할_수_있다() throws Exception {
		// 글 삭제 -> 방의 글 칸이 NULL(ON DELETE SET NULL), 방은 남는다
		jdbc.update("delete from market_items where id = ?", 글id);
		assertThat(jdbc.queryForObject("select market_item_id from conversations where id = ?", Long.class, 방id)).isNull();

		assertThat(신고(mockMvc, 판매자쿠키, 방id, 본문("ABUSE")).getStatus()).isEqualTo(201);
	}

	@Test
	void 같은_학교_검사는_하지_않는다_참가자의_인증_학교가_바뀌어도_201이다() throws Exception {
		TestLogin.학교_인증_처리(jdbc, "crbuyer", "고려대학교"); // 글은 연세대, 구매자는 이제 고려대

		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(201);
	}

	// ============ 409 ============

	@Test
	void 같은_사람이_같은_방을_또_신고하면_409이고_행은_1개다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(201);
		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("ABUSE")).getStatus()).isEqualTo(409);
		assertThat(신고행수()).isEqualTo(1);
	}

	// 두 요청이 existsBy 확인을 둘 다 통과해도 DB UNIQUE(uk_reports_conversation_reporter)가 하나만 살린다
	@Test
	void 같은_사람이_같은_방을_동시에_2번_신고하면_201_하나_409_하나이고_행은_1개다() throws Exception {
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
					return 신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM")).getStatus();
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
			assertThat(conflict).as("409 횟수").isEqualTo(1);
			assertThat(신고행수()).isEqualTo(1);
		} finally {
			pool.shutdownNow();
		}
	}

	// ============ 404 ============

	@Test
	void 참가자가_아닌_인증_회원은_404이고_저장되지_않는다() throws Exception {
		assertThat(신고(mockMvc, 제3자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(404);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 관리자도_남의_방은_404이다() throws Exception {
		assertThat(신고(mockMvc, 관리자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(404);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 없는_방_id는_404이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 999999999L, 본문("SPAM")).getStatus()).isEqualTo(404);
	}

	@Test
	void 탈퇴한_참가자_칸의_회원은_그_방을_신고할_수_없다_404() throws Exception {
		// 판매자 칸이 NULL이 된 방에서 제3자는 여전히 참가자가 아니다(NULL 칸이 "아무나"가 되지 않는다)
		jdbc.update("update conversations set seller_id = null where id = ?", 방id);
		assertThat(신고(mockMvc, 제3자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(404);
	}

	// ============ 403 ============

	@Test
	void 학교_인증을_안_한_회원은_403이다_참가자여도() throws Exception {
		jdbc.update("update users set verified_school_id = null where id = ?", 구매자.getId());

		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(403);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 미인증이면_없는_방이어도_404가_아니라_403이다_검사_순서() throws Exception {
		jdbc.update("update users set verified_school_id = null where id = ?", 구매자.getId());
		assertThat(신고(mockMvc, 구매자쿠키, 999999999L, 본문("SPAM")).getStatus()).isEqualTo(403);
	}

	@Test
	void CSRF_없이_신고하면_403이고_저장되지_않는다() throws Exception {
		int st = mockMvc.perform(post("/conversations/" + 방id + "/reports").cookie(구매자쿠키)
				.contentType(MediaType.APPLICATION_JSON).content(본문("SPAM"))).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(403);
		assertThat(신고행수()).isZero();
	}

	// ============ 401 ============

	@Test
	void 비로그인이면_401이다() throws Exception {
		assertThat(신고(mockMvc, null, 방id, 본문("SPAM")).getStatus()).isEqualTo(401);
		assertThat(신고행수()).isZero();
	}

	@Test
	void 탈퇴한_회원의_옛_토큰이면_401이다() throws Exception {
		assertThat(신고(mockMvc, TestLogin.행없는_회원_토큰(jwtEncoder), 방id, 본문("SPAM")).getStatus()).isEqualTo(401);
	}

	@Test
	void 실제로_탈퇴한_참가자의_옛_토큰이면_401이다() throws Exception {
		int st = mockMvc.perform(delete("/users/me").with(csrf()).cookie(구매자쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(204);

		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM")).getStatus()).isEqualTo(401);
		assertThat(신고행수()).isZero();
	}

	// ============ 400 ============

	@Test
	void reason이_없으면_400이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "{}").getStatus()).isEqualTo(400);
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "{\"detail\":\"x\"}").getStatus()).isEqualTo(400);
	}

	@Test
	void 모르는_reason이면_400이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("HATE")).getStatus()).isEqualTo(400);
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "{\"reason\":0}").getStatus()).isEqualTo(400);
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "{\"reason\":null}").getStatus()).isEqualTo(400);
	}

	@Test
	void detail이_301자면_400이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "{\"reason\":\"SPAM\",\"detail\":\"" + "가".repeat(301) + "\"}").getStatus())
				.isEqualTo(400);
	}

	@Test
	void detail에_NUL이_있으면_400이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "{\"reason\":\"SPAM\",\"detail\":\"a\\u0000b\"}").getStatus()).isEqualTo(400);
	}

	@Test
	void 본문이_깨졌거나_없으면_400이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "{not json").getStatus()).isEqualTo(400);
		assertThat(신고(mockMvc, 구매자쿠키, 방id, "").getStatus()).isEqualTo(400);
	}

	@Test
	void id가_숫자가_아니면_400이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, "abc", 본문("SPAM")).getStatus()).isEqualTo(400);
	}

	@Test
	void 잘못된_입력은_404_대상_검사보다_먼저_400이다() throws Exception {
		assertThat(신고(mockMvc, 제3자쿠키, 방id, 본문("HATE")).getStatus()).isEqualTo(400);
	}

	// ============ 탈퇴·DB 제약 ============

	@Test
	void 신고자가_탈퇴하면_신고는_남고_reporter_id만_NULL이다() throws Exception {
		assertThat(신고(mockMvc, 구매자쿠키, 방id, 본문("ABUSE")).getStatus()).isEqualTo(201);

		int st = mockMvc.perform(delete("/users/me").with(csrf()).cookie(구매자쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		assertThat(st).isEqualTo(204);

		assertThat(jdbc.queryForMap("select conversation_id, reporter_id from reports")).containsEntry("conversation_id", 방id)
				.containsEntry("reporter_id", null);
	}

	@Test
	void 방이_지워지면_그_방의_신고도_DB가_같이_지운다() throws Exception {
		신고(mockMvc, 구매자쿠키, 방id, 본문("SPAM"));
		assertThat(신고행수()).isEqualTo(1);

		jdbc.update("delete from conversations where id = ?", 방id);

		assertThat(신고행수()).isZero();
	}

	@Test
	void V13이_성공_기록으로_한_번_남고_제약_이름이_생긴다() {
		assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '13' and success = true",
				Integer.class)).isEqualTo(1);
		for (String name : new String[] { "fk_reports_conversation", "uk_reports_conversation_reporter", "ck_reports_target" }) {
			assertThat(jdbc.queryForObject("select count(*) from pg_constraint where conname = ?", Integer.class, name))
					.as("제약 " + name).isEqualTo(1);
		}
		// FK는 conversations만 가리키고 새 users FK는 없다(UsersForeignKeyGuardTests가 따로 지킨다)
		assertThat(jdbc.queryForObject("select confrelid::regclass::text from pg_constraint where conname = 'fk_reports_conversation'",
				String.class)).isEqualTo("conversations");
	}

	@Test
	void 대상이_둘이거나_없는_신고는_ck_reports_target이_막는다() {
		long reporter = 구매자.getId();
		// 중고 글 칸 + 대화 칸 둘 다 채움
		assertThatThrownBy(() -> jdbc.update("insert into reports (market_item_id, conversation_id, reporter_id, reason, status, created_at) "
				+ "values (?, ?, ?, 'SPAM', 'PENDING', now())", 글id, 방id, reporter))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_target");
		// 아무 칸도 안 채움
		assertThatThrownBy(() -> jdbc.update("insert into reports (reporter_id, reason, status, created_at) "
				+ "values (?, 'SPAM', 'PENDING', now())", reporter))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_target");
	}
}
