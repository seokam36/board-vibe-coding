package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
import org.springframework.transaction.PlatformTransactionManager;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S14-c 안 읽은 쪽지: PUT /conversations/{id}/read (본 쪽지까지만), GET /conversations/unread-count, 목록의 unreadCount
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationUnreadTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;

	User 판매자, 구매자, 제삼자;
	Cookie 판매자쿠키, 구매자쿠키, 제삼자쿠키;
	long 글id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "useller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "ubuyer", "연세대학교");
		제삼자 = TestLogin.인증된_사용자(userRepository, jdbc, "uthird", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		제삼자쿠키 = TestLogin.쿠키(tokenService, 제삼자);
		글id = 중고글_올리기(판매자쿠키, "자전거");
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private long 중고글_올리기(Cookie c, String title) throws Exception {
		String body = "{\"title\":\"" + title + "\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 중고 글 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private long 방_만들기(Cookie 보내는사람, long itemId, String content) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post("/market-items/" + itemId + "/conversations").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}").cookie(보내는사람))
				.andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 첫 쪽지").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("conversationId").asLong();
	}

	private void 답장(Cookie cookie, long roomId, String content) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post("/conversations/" + roomId + "/messages").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}").cookie(cookie))
				.andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 답장").isEqualTo(201);
	}

	private MockHttpServletResponse 읽음_원문(Cookie cookie, Object roomId, String rawJson) throws Exception {
		var req = put("/conversations/" + roomId + "/read").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(rawJson);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 읽음(Cookie cookie, Object roomId, long lastMessageId) throws Exception {
		return 읽음_원문(cookie, roomId, "{\"lastMessageId\":" + lastMessageId + "}");
	}

	private MockHttpServletResponse 안읽은수_응답(Cookie cookie) throws Exception {
		var req = get("/conversations/unread-count");
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private long 안읽은수(Cookie cookie) throws Exception {
		MockHttpServletResponse r = 안읽은수_응답(cookie);
		assertThat(r.getStatus()).isEqualTo(200);
		return om.readTree(r.getContentAsString()).get("count").asLong();
	}

	private JsonNode 목록(Cookie cookie) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(get("/conversations").cookie(cookie)).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		return om.readTree(r.getContentAsString());
	}

	// 방의 쪽지 id들(오름차순)
	private List<Long> 쪽지ids(long roomId) {
		return jdbc.queryForList("select id from messages where conversation_id = ? order by id", Long.class, roomId);
	}

	private Instant 읽은시각(long messageId) {
		var ts = jdbc.queryForObject("select read_at from messages where id = ?", java.sql.Timestamp.class, messageId);
		return ts == null ? null : ts.toInstant();
	}

	private List<Boolean> 읽혔나(List<Long> ids) {
		List<Boolean> out = new ArrayList<>();
		for (Long id : ids) {
			out.add(읽은시각(id) != null);
		}
		return out;
	}

	// ---------- 기본 흐름 ----------

	@Test
	void 구매자가_2개_보내면_판매자_2_구매자_0이고_판매자가_읽으면_0이_된다_내_쪽지는_안_바뀐다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		답장(구매자쿠키, 방, "둘");
		assertThat(안읽은수(판매자쿠키)).isEqualTo(2);
		assertThat(안읽은수(구매자쿠키)).as("보낸 사람 본인은 0").isZero();

		// 판매자가 답장 1개 보낸다 -> 구매자에겐 1개
		답장(판매자쿠키, 방, "답");
		assertThat(안읽은수(구매자쿠키)).isEqualTo(1);
		List<Long> ids = 쪽지ids(방);

		assertThat(읽음(판매자쿠키, 방, ids.get(2)).getStatus()).isEqualTo(204);
		assertThat(안읽은수(판매자쿠키)).isZero();
		assertThat(읽혔나(ids)).as("구매자가 보낸 2개만 읽힘, 판매자가 보낸 건 그대로").containsExactly(true, true, false);
		assertThat(안읽은수(구매자쿠키)).as("판매자가 읽어도 구매자 쪽 숫자는 그대로").isEqualTo(1);
	}

	@Test
	void 중간_번호까지만_읽으면_그_뒤_쪽지는_안_읽음으로_남는다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		답장(구매자쿠키, 방, "둘");
		답장(구매자쿠키, 방, "셋");
		List<Long> ids = 쪽지ids(방);

		assertThat(읽음(판매자쿠키, 방, ids.get(1)).getStatus()).isEqualTo(204);
		assertThat(읽혔나(ids)).containsExactly(true, true, false);
		assertThat(안읽은수(판매자쿠키)).as("셋째만 남음").isEqualTo(1);
		assertThat(목록(판매자쿠키).get(0).get("unreadCount").asLong()).isEqualTo(1);

		// 첫째 번호로 또 불러도(작은 번호) 뒤 쪽지는 여전히 그대로
		assertThat(읽음(판매자쿠키, 방, ids.get(0)).getStatus()).isEqualTo(204);
		assertThat(읽혔나(ids)).containsExactly(true, true, false);
	}

	@Test
	void 두_번_읽음_처리해도_204이고_처음_찍힌_시각이_그대로다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		long 쪽지 = 쪽지ids(방).get(0);

		assertThat(읽음(판매자쿠키, 방, 쪽지).getStatus()).isEqualTo(204);
		Instant 처음 = 읽은시각(쪽지);
		assertThat(처음).isNotNull();
		Thread.sleep(20);
		assertThat(읽음(판매자쿠키, 방, 쪽지).getStatus()).isEqualTo(204);
		assertThat(읽은시각(쪽지)).as("read_at is null 조건이라 안 바뀜").isEqualTo(처음);
	}

	@Test
	void lastMessageId가_방의_최대보다_커도_204이고_다른_방_번호여도_이_방_쪽지만_바뀐다() throws Exception {
		long 방1 = 방_만들기(구매자쿠키, 글id, "방1");
		long 글2 = 중고글_올리기(판매자쿠키, "책상");
		long 방2 = 방_만들기(구매자쿠키, 글2, "방2");
		long 방2쪽지 = 쪽지ids(방2).get(0);
		long 방1쪽지 = 쪽지ids(방1).get(0);

		// 방1 읽음 요청에 방2의 쪽지 번호(더 큼)를 넣는다: 방1 쪽지만 읽히고 방2 쪽지는 그대로
		assertThat(읽음(판매자쿠키, 방1, 방2쪽지).getStatus()).isEqualTo(204);
		assertThat(읽은시각(방1쪽지)).isNotNull();
		assertThat(읽은시각(방2쪽지)).as("다른 방 쪽지는 안 바뀜").isNull();
		assertThat(안읽은수(판매자쿠키)).isEqualTo(1);

		// 아주 큰 번호도 204
		assertThat(읽음(판매자쿠키, 방2, 9_000_000_000L).getStatus()).isEqualTo(204);
		assertThat(안읽은수(판매자쿠키)).isZero();
	}

	@Test
	void 상대가_탈퇴해_보낸_사람이_NULL인_쪽지도_안_읽음으로_세고_읽음_처리로_줄어든다() throws Exception {
		// 구매자가 쪽지를 보내고 탈퇴한다 -> messages.sender_id가 NULL이 된다. 판매자는 여전히 참가자.
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		답장(구매자쿠키, 방, "둘");
		jdbc.update("delete from users where id = ?", 구매자.getId());
		assertThat(jdbc.queryForObject("select count(*) from messages where sender_id is null", Integer.class)).isEqualTo(2);

		assertThat(안읽은수(판매자쿠키)).as("sender NULL 쪽지도 상대가 보낸 것으로 센다").isEqualTo(2);
		assertThat(목록(판매자쿠키).get(0).get("unreadCount").asLong()).isEqualTo(2);

		assertThat(읽음(판매자쿠키, 방, 쪽지ids(방).get(1)).getStatus()).isEqualTo(204);
		assertThat(안읽은수(판매자쿠키)).as("읽음 처리로 줄어든다").isZero();
		assertThat(목록(판매자쿠키).get(0).get("unreadCount").asLong()).isZero();
	}

	// ---------- 목록 ----------

	@Test
	void 목록의_unreadCount는_방마다_그_방의_안_읽은_수이고_없으면_0이다() throws Exception {
		long 방1 = 방_만들기(구매자쿠키, 글id, "방1-하나");
		답장(구매자쿠키, 방1, "방1-둘");
		long 글2 = 중고글_올리기(판매자쿠키, "책상");
		long 방2 = 방_만들기(구매자쿠키, 글2, "방2-하나");
		long 글3 = 중고글_올리기(제삼자쿠키, "의자");
		방_만들기(판매자쿠키, 글3, "방3-판매자가 보냄");

		JsonNode 판매자목록 = 목록(판매자쿠키);
		assertThat(판매자목록).hasSize(3);
		for (JsonNode 항목 : 판매자목록) {
			long id = 항목.get("id").asLong();
			long 기대 = id == 방1 ? 2 : id == 방2 ? 1 : 0; // 방3은 내가 보낸 것뿐
			assertThat(항목.get("unreadCount").asLong()).as("방 " + id).isEqualTo(기대);
		}
		assertThat(안읽은수(판매자쿠키)).isEqualTo(3);

		// 방3은 제삼자(판매자 입장 상대)에게 1개
		assertThat(안읽은수(제삼자쿠키)).isEqualTo(1);
		assertThat(목록(제삼자쿠키).get(0).get("unreadCount").asLong()).isEqualTo(1);
	}

	@Test
	void 대화가_없으면_개수_0이고_대화_보기만으로는_읽음_처리되지_않는다() throws Exception {
		assertThat(안읽은수(구매자쿠키)).isZero();
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		mockMvc.perform(get("/conversations/" + 방).cookie(판매자쿠키)).andReturn();
		assertThat(안읽은수(판매자쿠키)).as("GET 보기는 읽음을 찍지 않는다(PUT read로만)").isEqualTo(1);
	}

	// ---------- 읽음 처리 오류들 ----------

	@Test
	void 남의_방과_없는_방은_404이고_관리자도_404이며_쪽지는_안_바뀐다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		long 쪽지 = 쪽지ids(방).get(0);

		assertThat(읽음(제삼자쿠키, 방, 쪽지).getStatus()).as("제삼자").isEqualTo(404);
		User 관리자 = TestLogin.관리자(userRepository, jdbc, "uadmin");
		Cookie 관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		assertThat(읽음(관리자쿠키, 방, 쪽지).getStatus()).as("관리자").isEqualTo(404);
		assertThat(읽음(판매자쿠키, 99999999L, 쪽지).getStatus()).as("없는 방").isEqualTo(404);
		assertThat(읽은시각(쪽지)).isNull();
	}

	@Test
	void 입력이_틀리면_400이다_lastMessageId_없음_null_0이하_숫자아님_방id_숫자아님() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		assertThat(읽음_원문(판매자쿠키, 방, "{}").getStatus()).as("없음").isEqualTo(400);
		assertThat(읽음_원문(판매자쿠키, 방, "{\"lastMessageId\":null}").getStatus()).as("null").isEqualTo(400);
		assertThat(읽음_원문(판매자쿠키, 방, "{\"lastMessageId\":0}").getStatus()).as("0").isEqualTo(400);
		assertThat(읽음_원문(판매자쿠키, 방, "{\"lastMessageId\":-5}").getStatus()).as("음수").isEqualTo(400);
		assertThat(읽음_원문(판매자쿠키, 방, "{\"lastMessageId\":\"abc\"}").getStatus()).as("숫자 아님").isEqualTo(400);
		assertThat(읽음_원문(판매자쿠키, 방, "{깨진 json").getStatus()).as("JSON 오류").isEqualTo(400);
		assertThat(읽음_원문(판매자쿠키, 방, "").getStatus()).as("본문 없음").isEqualTo(400);
		assertThat(읽음_원문(판매자쿠키, "abc", "{\"lastMessageId\":1}").getStatus()).as("방 id 숫자 아님").isEqualTo(400);
		assertThat(안읽은수(판매자쿠키)).as("전부 거절돼 그대로").isEqualTo(1);
	}

	@Test
	void 로그인_없음_401_유령_토큰_401_CSRF_없으면_403이고_학교_미인증_회원도_읽음_처리된다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		long 쪽지 = 쪽지ids(방).get(0);

		assertThat(읽음(null, 방, 쪽지).getStatus()).as("쿠키 없음").isEqualTo(401);
		assertThat(읽음(TestLogin.행없는_회원_토큰(jwtEncoder), 방, 쪽지).getStatus()).as("유령 토큰").isEqualTo(401);
		// CSRF 헤더 없이 PUT
		int csrf없음 = mockMvc.perform(put("/conversations/" + 방 + "/read").contentType(MediaType.APPLICATION_JSON)
				.content("{\"lastMessageId\":" + 쪽지 + "}").cookie(판매자쿠키)).andReturn().getResponse().getStatus();
		assertThat(csrf없음).as("CSRF 없음").isEqualTo(403);
		assertThat(읽은시각(쪽지)).isNull();

		// 판매자의 학교 인증을 풀어도(읽기 계열이라) 읽음 처리는 된다
		jdbc.update("update users set verified_school_id = null, verified_at = null where id = ?", 판매자.getId());
		assertThat(읽음(판매자쿠키, 방, 쪽지).getStatus()).as("미인증도 204").isEqualTo(204);
		assertThat(읽은시각(쪽지)).isNotNull();
	}

	// ---------- 안 읽은 수 경로 ----------

	@Test
	void unread_count는_대화_id로_잡히지_않고_200이며_로그인_없음_유령_토큰은_401이다() throws Exception {
		방_만들기(구매자쿠키, 글id, "하나");
		MockHttpServletResponse r = 안읽은수_응답(판매자쿠키);
		assertThat(r.getStatus()).as("{id}로 잡혀 400이 나면 안 된다").isEqualTo(200);
		JsonNode 본문 = om.readTree(r.getContentAsString());
		List<String> 키 = new ArrayList<>();
		본문.propertyNames().forEach(키::add);
		assertThat(키).containsExactly("count");
		assertThat(본문.get("count").asLong()).isEqualTo(1);

		assertThat(안읽은수_응답(null).getStatus()).isEqualTo(401);
		assertThat(안읽은수_응답(TestLogin.행없는_회원_토큰(jwtEncoder)).getStatus()).isEqualTo(401);
		// 숫자 id 방 보기는 그대로 동작한다(경로가 서로 안 가린다)
		long 방 = jdbc.queryForObject("select id from conversations", Long.class);
		assertThat(mockMvc.perform(get("/conversations/" + 방).cookie(판매자쿠키)).andReturn().getResponse().getStatus())
				.isEqualTo(200);
		assertThat(mockMvc.perform(get("/conversations/abc").cookie(판매자쿠키)).andReturn().getResponse().getStatus())
				.isEqualTo(400);
	}

	// ---------- 경쟁: 읽음 처리와 상대 탈퇴가 겹쳐도 500이 없다 ----------

	// 탈퇴가 회원 행을 잡고 쪽지 행의 sender_id를 NULL로 바꾸는 중(커밋 전)에 읽음 요청이 온다.
	// 읽음 UPDATE는 같은 쪽지 행에서 기다리다가, 탈퇴 커밋 뒤 깨어나 "sender NULL = 상대가 보낸 것"으로 읽음 처리해야 한다.
	@Test
	void 상대_탈퇴가_쪽지_행을_잡고_있는_동안_읽음_요청은_기다렸다가_204이고_NULL_쪽지도_읽힌다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		답장(구매자쿠키, 방, "둘");
		long bid = 구매자.getId();
		long 마지막 = 쪽지ids(방).get(1);

		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> {
					jdbc.queryForList("select id from users where id = ? for update", Long.class, bid);
					jdbc.update("update messages set sender_id = null where sender_id = ?", bid); // 쪽지 행 잠금(커밋 전)
				},
				() -> {
					try {
						return 읽음(판매자쿠키, 방, 마지막).getStatus();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				},
				() -> jdbc.update("delete from users where id = ?", bid));

		assertThat(status).as("500·교착 없이 204").isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from messages where read_at is not null and sender_id is null",
				Integer.class)).as("탈퇴로 NULL이 된 쪽지도 읽힘").isEqualTo(2);
		assertThat(안읽은수(판매자쿠키)).isZero();
	}

	// 탈퇴가 "회원 행"만 FOR UPDATE로 쥐고 있는 동안(쪽지 행은 아직 안 건드림) 읽음 요청이 오면,
	// 읽음은 쪽지 UPDATE로 가기 전에 상대 회원 행 잠금(lockForKeyShare)에서 먼저 기다려야 한다.
	// ConversationService.markRead에서 그 잠금 줄을 빼면 읽음이 바로 끝나 "대기 중이어야 한다" 단언에서 실패한다.
	@Test
	void 상대_탈퇴가_회원_행만_잡고_있어도_읽음_요청은_회원_행_잠금에서_기다렸다가_204이고_NULL_쪽지도_읽힌다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "하나");
		답장(구매자쿠키, 방, "둘");
		long bid = 구매자.getId();
		long 마지막 = 쪽지ids(방).get(1);

		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from users where id = ? for update", Long.class, bid), // 탈퇴의 첫 잠금
				() -> {
					try {
						return 읽음(판매자쿠키, 방, 마지막).getStatus();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				},
				() -> jdbc.update("delete from users where id = ?", bid)); // 탈퇴 마무리(쪽지 sender -> NULL) 후 커밋

		assertThat(status).as("500·교착 없이 204").isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from messages where read_at is not null and sender_id is null",
				Integer.class)).as("탈퇴 뒤에 깨어나서도 NULL 쪽지가 읽힘").isEqualTo(2);
		assertThat(안읽은수(판매자쿠키)).isZero();
	}
}
