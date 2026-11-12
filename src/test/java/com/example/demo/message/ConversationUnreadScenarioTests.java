package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S14-c 안 읽은 쪽지: ConversationUnreadTests가 못 다룬 것 보충
// (여러 방 합계·대칭, 남의 방 섞임, 탈퇴·글 삭제 방, 화면 흐름, 검사 순서)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationUnreadScenarioTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;

	User 가, 나, 다;
	Cookie 가쿠키, 나쿠키, 다쿠키;

	@BeforeEach
	void 준비() {
		정리();
		가 = TestLogin.인증된_사용자(userRepository, jdbc, "sc_a", "연세대학교");
		나 = TestLogin.인증된_사용자(userRepository, jdbc, "sc_b", "연세대학교");
		다 = TestLogin.인증된_사용자(userRepository, jdbc, "sc_c", "연세대학교");
		가쿠키 = TestLogin.쿠키(tokenService, 가);
		나쿠키 = TestLogin.쿠키(tokenService, 나);
		다쿠키 = TestLogin.쿠키(tokenService, 다);
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private long 글_올리기(Cookie c, String title) throws Exception {
		String body = "{\"title\":\"" + title + "\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private long 방_만들기(Cookie 보내는사람, long itemId, String content) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post("/market-items/" + itemId + "/conversations").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}").cookie(보내는사람))
				.andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("conversationId").asLong();
	}

	private void 답장(Cookie cookie, long roomId, String content) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post("/conversations/" + roomId + "/messages").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}").cookie(cookie))
				.andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(201);
	}

	private MockHttpServletResponse 읽음(Cookie cookie, Object roomId, String rawJson) throws Exception {
		var req = put("/conversations/" + roomId + "/read").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(rawJson);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private int 읽음_상태(Cookie cookie, Object roomId, long last) throws Exception {
		return 읽음(cookie, roomId, "{\"lastMessageId\":" + last + "}").getStatus();
	}

	private long 안읽은수(Cookie cookie) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(get("/conversations/unread-count").cookie(cookie)).andReturn()
				.getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		return om.readTree(r.getContentAsString()).get("count").asLong();
	}

	private JsonNode 목록(Cookie cookie) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(get("/conversations").cookie(cookie)).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		return om.readTree(r.getContentAsString());
	}

	private long 목록_합계(Cookie cookie) throws Exception {
		long sum = 0;
		for (JsonNode n : 목록(cookie)) {
			sum += n.get("unreadCount").asLong();
		}
		return sum;
	}

	private long 방의_안읽은수(Cookie cookie, long roomId) throws Exception {
		for (JsonNode n : 목록(cookie)) {
			if (n.get("id").asLong() == roomId) {
				return n.get("unreadCount").asLong();
			}
		}
		throw new AssertionError("목록에 방 " + roomId + " 없음");
	}

	private List<Long> 쪽지ids(long roomId) {
		return jdbc.queryForList("select id from messages where conversation_id = ? order by id", Long.class, roomId);
	}

	// 대화 보기 응답에서 마지막 쪽지 id (화면이 PUT에 보내는 값)
	private long 화면이_보내는_마지막id(Cookie cookie, long roomId) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(get("/conversations/" + roomId).cookie(cookie)).andReturn()
				.getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode msgs = om.readTree(r.getContentAsString()).get("messages");
		return msgs.get(msgs.size() - 1).get("id").asLong();
	}

	// ---------- 1. 여러 방 합계 + 양쪽 대칭 ----------

	@Test
	void 구매자인_방과_판매자인_방이_섞여도_count는_모든_방_합계이고_목록_합과_같으며_남의_방은_안_섞인다() throws Exception {
		// 방X: 가=판매자, 나=구매자 (나가 2통 보냄)
		long 글가 = 글_올리기(가쿠키, "가의 자전거");
		long 방X = 방_만들기(나쿠키, 글가, "X1");
		답장(나쿠키, 방X, "X2");
		// 방Y: 다=판매자, 가=구매자 (가가 1통, 다가 3통 답장)
		long 글다 = 글_올리기(다쿠키, "다의 책상");
		long 방Y = 방_만들기(가쿠키, 글다, "Y1");
		답장(다쿠키, 방Y, "Y2");
		답장(다쿠키, 방Y, "Y3");
		답장(다쿠키, 방Y, "Y4");
		// 방Z: 가가 전혀 없는 방 (다=판매자, 나=구매자, 나가 5통) -> 가의 숫자에 섞이면 안 됨
		long 방Z = 방_만들기(나쿠키, 글다, "Z1");
		for (int i = 2; i <= 5; i++) {
			답장(나쿠키, 방Z, "Z" + i);
		}

		assertThat(안읽은수(가쿠키)).as("가: 방X 2(판매자) + 방Y 3(구매자), 방Z는 남의 방").isEqualTo(5);
		assertThat(목록_합계(가쿠키)).as("count == 목록 unreadCount 합").isEqualTo(5);
		assertThat(목록(가쿠키)).hasSize(2);
		assertThat(방의_안읽은수(가쿠키, 방X)).isEqualTo(2);
		assertThat(방의_안읽은수(가쿠키, 방Y)).isEqualTo(3);
		assertThat(안읽은수(나쿠키)).as("나: 방X에서 보낸 것뿐, 방Z도 본인이 보냄").isZero();
		assertThat(안읽은수(다쿠키)).as("다: 방Y 1(가가 보냄) + 방Z 5").isEqualTo(6);
		assertThat(목록_합계(다쿠키)).isEqualTo(6);

		// 구매자(가)가 방Y를 읽음 -> 방X는 그대로 (대칭: 구매자가 판매자 쪽지를 읽음)
		assertThat(읽음_상태(가쿠키, 방Y, 쪽지ids(방Y).get(3))).isEqualTo(204);
		assertThat(안읽은수(가쿠키)).isEqualTo(2);
		assertThat(방의_안읽은수(가쿠키, 방Y)).isZero();
		assertThat(안읽은수(다쿠키)).as("다 쪽 숫자는 가가 읽어도 그대로").isEqualTo(6);

		// 판매자(가)가 방X를 읽음 -> 0
		assertThat(읽음_상태(가쿠키, 방X, 쪽지ids(방X).get(1))).isEqualTo(204);
		assertThat(안읽은수(가쿠키)).isZero();
		assertThat(목록_합계(가쿠키)).isZero();
		assertThat(안읽은수(나쿠키)).isZero();
	}

	// ---------- 2. 상대 탈퇴·글 삭제 방 ----------

	@Test
	void 내가_구매자인_방의_판매자가_탈퇴해도_남은_쪽_숫자에_세지고_읽음_처리도_204이다() throws Exception {
		long 글 = 글_올리기(가쿠키, "곧 탈퇴");
		long 방 = 방_만들기(나쿠키, 글, "문의");
		답장(가쿠키, 방, "답1");
		답장(가쿠키, 방, "답2");
		jdbc.update("delete from users where id = ?", 가.getId()); // 판매자 탈퇴 -> 글 삭제 + seller_id NULL + sender NULL

		assertThat(안읽은수(나쿠키)).as("탈퇴한 상대의 쪽지 2통").isEqualTo(2);
		assertThat(방의_안읽은수(나쿠키, 방)).isEqualTo(2);
		assertThat(읽음_상태(나쿠키, 방, 쪽지ids(방).get(2))).isEqualTo(204);
		assertThat(안읽은수(나쿠키)).isZero();
		assertThat(방의_안읽은수(나쿠키, 방)).isZero();
	}

	@Test
	void 글이_삭제된_방에서도_읽음_처리_204이고_숫자가_반영된다() throws Exception {
		long 글 = 글_올리기(가쿠키, "삭제될 글");
		long 방 = 방_만들기(나쿠키, 글, "하나");
		jdbc.update("delete from market_items where id = ?", 글);
		assertThat(jdbc.queryForObject("select market_item_id from conversations where id = ?", Long.class, 방))
				.as("글 칸이 NULL").isNull();

		assertThat(안읽은수(가쿠키)).isEqualTo(1);
		assertThat(방의_안읽은수(가쿠키, 방)).isEqualTo(1);
		assertThat(읽음_상태(가쿠키, 방, 쪽지ids(방).get(0))).isEqualTo(204);
		assertThat(안읽은수(가쿠키)).isZero();
	}

	@Test
	void 내가_탈퇴한_뒤_남은_상대_쪽에서_내_쪽지는_세져도_탈퇴한_내_토큰은_401이다() throws Exception {
		long 글 = 글_올리기(가쿠키, "자전거");
		long 방 = 방_만들기(나쿠키, 글, "하나");
		jdbc.update("delete from users where id = ?", 나.getId()); // 구매자 탈퇴

		assertThat(안읽은수(가쿠키)).as("상대가 탈퇴해도 판매자의 안 읽은 수는 남는다").isEqualTo(1);
		MockHttpServletResponse r = mockMvc.perform(get("/conversations/unread-count").cookie(나쿠키)).andReturn()
				.getResponse();
		assertThat(r.getStatus()).as("탈퇴한 회원 토큰").isEqualTo(401);
		assertThat(읽음_상태(나쿠키, 방, 1)).isEqualTo(401);
	}

	// ---------- 3. 화면 흐름 ----------

	@Test
	void 화면_흐름_답장이_오면_목록_숫자가_늘고_대화_마지막_id로_읽으면_0이며_그_사이_온_새_답장만_남는다() throws Exception {
		long 글 = 글_올리기(가쿠키, "자전거");
		long 방 = 방_만들기(나쿠키, 글, "안녕하세요");
		assertThat(방의_안읽은수(가쿠키, 방)).isEqualTo(1);
		답장(나쿠키, 방, "아직 있나요");
		assertThat(방의_안읽은수(가쿠키, 방)).as("답장이 오면 늘어난다").isEqualTo(2);

		// 판매자가 대화를 연다: GET 응답의 마지막 id를 기억해 둔다
		long 열때_마지막 = 화면이_보내는_마지막id(가쿠키, 방);
		// 열려 있는 동안 구매자의 새 답장이 도착한다
		답장(나쿠키, 방, "열려 있는 동안 온 답장");
		assertThat(안읽은수(가쿠키)).isEqualTo(3);

		// 화면이 기억한 마지막 id로 읽음 처리 -> 새 답장 1통만 남는다
		assertThat(읽음_상태(가쿠키, 방, 열때_마지막)).isEqualTo(204);
		assertThat(안읽은수(가쿠키)).as("새 것만 남음").isEqualTo(1);
		assertThat(방의_안읽은수(가쿠키, 방)).isEqualTo(1);

		// 다시 열어 새 마지막 id로 읽으면 0
		assertThat(읽음_상태(가쿠키, 방, 화면이_보내는_마지막id(가쿠키, 방))).isEqualTo(204);
		assertThat(안읽은수(가쿠키)).isZero();
		assertThat(방의_안읽은수(가쿠키, 방)).isZero();
		assertThat(안읽은수(나쿠키)).as("구매자 쪽은 처음부터 0").isZero();
	}

	// ---------- 4. 검사 순서 (api.md 40) ----------

	@Test
	void 검사_순서_CSRF_403이_로그인_401보다_먼저이고_로그인_401이_본문_400보다_먼저다() throws Exception {
		long 글 = 글_올리기(가쿠키, "자전거");
		long 방 = 방_만들기(나쿠키, 글, "하나");

		// 쿠키도 CSRF도 없음 -> 403
		int 둘다없음 = mockMvc.perform(put("/conversations/" + 방 + "/read").contentType(MediaType.APPLICATION_JSON)
				.content("{}")).andReturn().getResponse().getStatus();
		assertThat(둘다없음).as("CSRF 없음 + 로그인 없음").isEqualTo(403);
		// CSRF만 있고 로그인 없음 + 본문도 틀림 -> 401
		assertThat(읽음(null, 방, "{}").getStatus()).as("로그인 없음 + 본문 틀림").isEqualTo(401);
		// 로그인 + CSRF 없음 + 본문 틀림 -> 403
		int csrf없음_본문틀림 = mockMvc.perform(put("/conversations/" + 방 + "/read")
				.contentType(MediaType.APPLICATION_JSON).content("{}").cookie(가쿠키)).andReturn().getResponse().getStatus();
		assertThat(csrf없음_본문틀림).isEqualTo(403);
	}

	@Test
	void 검사_순서_본문_400이_회원_없음_401과_방_404보다_먼저다() throws Exception {
		long 글 = 글_올리기(가쿠키, "자전거");
		long 방 = 방_만들기(나쿠키, 글, "하나");

		// 없는 방 + 본문 틀림 -> 404가 아니라 400
		assertThat(읽음(가쿠키, 99999999L, "{}").getStatus()).as("없는 방 + 본문 틀림").isEqualTo(400);
		// 남의 방 + 본문 틀림 -> 400
		assertThat(읽음(다쿠키, 방, "{\"lastMessageId\":0}").getStatus()).as("남의 방 + 본문 틀림").isEqualTo(400);
		// DB에 없는 회원(유효한 토큰) + 본문 틀림 -> 400, 본문이 맞으면 401
		Cookie 유령 = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(읽음(유령, 방, "{}").getStatus()).as("유령 + 본문 틀림").isEqualTo(400);
		assertThat(읽음_상태(유령, 방, 1)).as("유령 + 본문 맞음").isEqualTo(401);
		// 본문이 맞으면 그제서야 404
		assertThat(읽음_상태(다쿠키, 방, 1)).as("남의 방 + 본문 맞음").isEqualTo(404);
		assertThat(읽음_상태(가쿠키, 99999999L, 1)).as("없는 방 + 본문 맞음").isEqualTo(404);
	}

	@Test
	void 아주_큰_lastMessageId와_Long_범위를_넘는_숫자도_500이_아니다() throws Exception {
		long 글 = 글_올리기(가쿠키, "자전거");
		long 방 = 방_만들기(나쿠키, 글, "하나");
		assertThat(읽음_상태(가쿠키, 방, Long.MAX_VALUE)).isEqualTo(204);
		int 넘침 = 읽음(가쿠키, 방, "{\"lastMessageId\":99999999999999999999}").getStatus();
		assertThat(넘침).as("Long 범위를 넘는 숫자는 400").isEqualTo(400);
	}

	// api.md 40은 lastMessageId를 "1 이상의 정수"라고 적었다. 소수는 400이어야 하는지 확인한다.
	@Test
	void lastMessageId가_소수면_400이다() throws Exception {
		long 글 = 글_올리기(가쿠키, "자전거");
		long 방 = 방_만들기(나쿠키, 글, "하나");
		int 소수 = 읽음(가쿠키, 방, "{\"lastMessageId\":1.5}").getStatus();
		assertThat(소수).as("정수가 아닌 값").isEqualTo(400);
		assertThat(안읽은수(가쿠키)).as("거절됐으면 그대로").isEqualTo(1);
		// 소수 모양은 값이 정수여도(1.0, 1e2) 전부 400
		assertThat(읽음(가쿠키, 방, "{\"lastMessageId\":1.0}").getStatus()).as("1.0").isEqualTo(400);
		assertThat(읽음(가쿠키, 방, "{\"lastMessageId\":1e2}").getStatus()).as("1e2").isEqualTo(400);
		assertThat(안읽은수(가쿠키)).as("전부 거절돼 그대로").isEqualTo(1);
	}

	// 숫자 모양 문자열은 다른 요청 본문과 같이 받아진다(지금 동작 고정).
	// Jackson 전역 설정(문자열 -> 숫자 변환 허용 여부)을 바꾸면 이 단언도 같이 바뀔 것이다.
	@Test
	void lastMessageId가_숫자_모양_문자열이면_다른_요청과_같이_받아져_204이다() throws Exception {
		long 글 = 글_올리기(가쿠키, "자전거");
		long 방 = 방_만들기(나쿠키, 글, "하나");
		long 쪽지 = 쪽지ids(방).get(0);
		assertThat(읽음(가쿠키, 방, "{\"lastMessageId\":\"" + 쪽지 + "\"}").getStatus()).isEqualTo(204);
		assertThat(안읽은수(가쿠키)).as("실제로 읽음 처리됨").isZero();
	}

	// 읽기 계열이라 학교 미인증 회원도 안 읽은 수·목록의 unreadCount를 받는다.
	@Test
	void 학교_미인증_회원도_unread_count와_목록_unreadCount가_200이다() throws Exception {
		long 글 = 글_올리기(가쿠키, "자전거");
		long 방 = 방_만들기(나쿠키, 글, "하나");
		jdbc.update("update users set verified_school_id = null, verified_at = null where id = ?", 가.getId());

		assertThat(안읽은수(가쿠키)).isEqualTo(1); // 안에서 200 확인
		assertThat(방의_안읽은수(가쿠키, 방)).isEqualTo(1); // 목록도 200
	}
}
