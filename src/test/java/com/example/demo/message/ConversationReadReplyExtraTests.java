package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S14-b 보충 검사: 응답 키가 정확한지, 본문 400 종류, 검사 순서 조합, 사람들이 섞이지 않는지
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationReadReplyExtraTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;

	User 판매자, 구매자, 제삼자;
	Cookie 판매자쿠키, 구매자쿠키, 제삼자쿠키;
	long 글id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "xseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "xbuyer", "연세대학교");
		제삼자 = TestLogin.인증된_사용자(userRepository, jdbc, "xthird", "연세대학교");
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

	// 본문 문자열을 그대로(JSON 원문) 보낸다
	private MockHttpServletResponse 원문답장(Cookie cookie, Object roomId, String rawJson) throws Exception {
		var req = post("/conversations/" + roomId + "/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(rawJson);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 답장(Cookie cookie, Object roomId, String content) throws Exception {
		return 원문답장(cookie, roomId, "{\"content\":\"" + content + "\"}");
	}

	private MockHttpServletResponse 목록(Cookie cookie) throws Exception {
		return mockMvc.perform(get("/conversations").cookie(cookie)).andReturn().getResponse();
	}

	private MockHttpServletResponse 보기(Cookie cookie, Object roomId) throws Exception {
		return mockMvc.perform(get("/conversations/" + roomId).cookie(cookie)).andReturn().getResponse();
	}

	private JsonNode json(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	private List<String> 키들(JsonNode n) {
		List<String> keys = new ArrayList<>();
		n.propertyNames().forEach(keys::add);
		return keys;
	}

	// ---------- 1, 2, 3. 응답 키가 정확히 명세뿐인지 ----------

	@Test
	void 응답_키는_명세에_적힌_것뿐이다_목록_보기_답장() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");

		JsonNode 목록항목 = json(목록(구매자쿠키)).get(0);
		assertThat(키들(목록항목)).containsExactlyInAnyOrder("id", "item", "partner", "lastMessage", "unreadCount");
		assertThat(키들(목록항목.get("item"))).containsExactlyInAnyOrder("id", "title", "deleted");
		assertThat(키들(목록항목.get("partner"))).containsExactlyInAnyOrder("id", "nickname");
		assertThat(키들(목록항목.get("lastMessage"))).containsExactlyInAnyOrder("content", "createdAt", "mine");

		JsonNode 상세 = json(보기(구매자쿠키, 방));
		assertThat(키들(상세)).containsExactlyInAnyOrder("id", "item", "partner", "messages", "hasMore");
		assertThat(키들(상세.get("item"))).containsExactlyInAnyOrder("id", "title", "deleted");
		assertThat(키들(상세.get("partner"))).containsExactlyInAnyOrder("id", "nickname");
		assertThat(키들(상세.get("messages").get(0))).containsExactlyInAnyOrder("id", "content", "createdAt", "mine");

		JsonNode 답 = json(답장(판매자쿠키, 방, "네"));
		assertThat(키들(답)).containsExactlyInAnyOrder("id", "content", "createdAt", "mine");
	}

	// ---------- 3. 본문 400 종류 + 경계 ----------

	@Test
	void 답장_본문이_없거나_비었거나_NUL이면_400이고_1000자는_201이다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		assertThat(원문답장(구매자쿠키, 방, "{}").getStatus()).as("content 없음").isEqualTo(400);
		assertThat(원문답장(구매자쿠키, 방, "{\"content\":null}").getStatus()).as("content null").isEqualTo(400);
		assertThat(답장(구매자쿠키, 방, "").getStatus()).as("빈 값").isEqualTo(400);
		assertThat(답장(구매자쿠키, 방, "   ").getStatus()).as("공백").isEqualTo(400);
		assertThat(답장(구매자쿠키, 방, "가".repeat(1001)).getStatus()).as("1001자").isEqualTo(400);
		assertThat(원문답장(구매자쿠키, 방, "{\"content\":\"a\\u0000b\"}").getStatus()).as("NUL").isEqualTo(400);
		assertThat(원문답장(구매자쿠키, 방, "{깨진 json").getStatus()).as("JSON 오류").isEqualTo(400);
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).as("저장 안 됨").isEqualTo(1);

		assertThat(답장(구매자쿠키, 방, "가".repeat(1000)).getStatus()).as("1000자").isEqualTo(201);
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).isEqualTo(2);
	}

	@Test
	void 판매자_답장은_판매자_기준_mine_true이고_구매자가_보면_mine_false다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		JsonNode 답 = json(답장(판매자쿠키, 방, "판매자 답"));
		assertThat(답.get("mine").asBoolean()).isTrue();

		JsonNode 구매자가본 = json(보기(구매자쿠키, 방)).get("messages");
		assertThat(구매자가본.get(1).get("content").asText()).isEqualTo("판매자 답");
		assertThat(구매자가본.get(1).get("mine").asBoolean()).isFalse();
		assertThat(구매자가본.get(1).get("id").asLong()).isEqualTo(답.get("id").asLong());
	}

	// ---------- 4. 상대 탈퇴 / 해제된 참가자 ----------

	@Test
	void 인증이_풀린_기존_참가자의_답장은_403이고_상대_탈퇴_방은_답장_409_보기_200이다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		jdbc.update("update users set verified_school_id = null where id = ?", 판매자.getId());
		assertThat(답장(판매자쿠키, 방, "x").getStatus()).isEqualTo(403);
		assertThat(보기(판매자쿠키, 방).getStatus()).isEqualTo(200);

		jdbc.update("delete from users where id = ?", 판매자.getId());
		assertThat(답장(구매자쿠키, 방, "x").getStatus()).isEqualTo(409);
		assertThat(보기(구매자쿠키, 방).getStatus()).isEqualTo(200);
	}

	// ---------- 5. 검사 순서 조합 ----------

	@Test
	void 순서_CSRF가_없으면_로그인_여부와_상관없이_403이다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		// api.md: CSRF 403 -> 로그인 401. 비로그인 + CSRF 없음 = 403
		int 비로그인 = mockMvc.perform(post("/conversations/" + 방 + "/messages").contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"x\"}")).andReturn().getResponse().getStatus();
		assertThat(비로그인).isEqualTo(403);
		// 본문이 잘못이어도 CSRF가 먼저
		int 본문오류 = mockMvc.perform(post("/conversations/" + 방 + "/messages").contentType(MediaType.APPLICATION_JSON)
				.content("{}").cookie(구매자쿠키)).andReturn().getResponse().getStatus();
		assertThat(본문오류).isEqualTo(403);
	}

	@Test
	void 순서_본문_400이_회원_없음_401보다_먼저다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		Cookie 유령 = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(답장(유령, 방, "   ").getStatus()).as("유령 + 공백 본문").isEqualTo(400);
		assertThat(답장(유령, 방, "정상").getStatus()).as("유령 + 정상 본문").isEqualTo(401);
	}

	@Test
	void 순서_미인증_403이_없는_방_404보다_먼저다() throws Exception {
		User 미인증 = TestLogin.사용자(userRepository, "xunverified");
		Cookie c = TestLogin.쿠키(tokenService, 미인증);
		assertThat(답장(c, 999999999L, "x").getStatus()).isEqualTo(403);
	}

	@Test
	void 순서_비참가자는_상대가_탈퇴한_방에서도_404이고_409가_아니다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		jdbc.update("delete from users where id = ?", 판매자.getId());
		assertThat(답장(제삼자쿠키, 방, "끼어들기").getStatus()).isEqualTo(404);
		assertThat(보기(제삼자쿠키, 방).getStatus()).isEqualTo(404);
		assertThat(답장(구매자쿠키, 방, "참가자").getStatus()).as("참가자는 409").isEqualTo(409);
	}

	// ---------- 7. 사람이 섞이지 않는지 ----------

	@Test
	void 세_회원_여러_방에서_각자_목록에_자기_방만_나오고_상대가_맞다() throws Exception {
		// A=판매자, B=구매자, C=제삼자. 글1은 A, 글2는 B가 판다.
		long 글2 = 중고글_올리기(구매자쿠키, "책상");
		long 방_B_to_A = 방_만들기(구매자쿠키, 글id, "B가 A에게");
		long 방_C_to_A = 방_만들기(제삼자쿠키, 글id, "C가 A에게");
		long 방_A_to_B = 방_만들기(판매자쿠키, 글2, "A가 B에게");
		long 방_C_to_B = 방_만들기(제삼자쿠키, 글2, "C가 B에게");

		// A의 방: B_to_A, C_to_A, A_to_B (C_to_B는 없다)
		JsonNode a = json(목록(판매자쿠키));
		assertThat(ids(a)).containsExactlyInAnyOrder(방_B_to_A, 방_C_to_A, 방_A_to_B);
		assertThat(상대(a, 방_B_to_A)).isEqualTo(구매자.getId());
		assertThat(상대(a, 방_C_to_A)).isEqualTo(제삼자.getId());
		assertThat(상대(a, 방_A_to_B)).isEqualTo(구매자.getId());

		// B의 방: B_to_A, A_to_B, C_to_B
		JsonNode b = json(목록(구매자쿠키));
		assertThat(ids(b)).containsExactlyInAnyOrder(방_B_to_A, 방_A_to_B, 방_C_to_B);
		assertThat(상대(b, 방_B_to_A)).isEqualTo(판매자.getId());
		assertThat(상대(b, 방_A_to_B)).isEqualTo(판매자.getId());
		assertThat(상대(b, 방_C_to_B)).isEqualTo(제삼자.getId());

		// C의 방: C_to_A, C_to_B
		JsonNode c = json(목록(제삼자쿠키));
		assertThat(ids(c)).containsExactlyInAnyOrder(방_C_to_A, 방_C_to_B);
		assertThat(상대(c, 방_C_to_A)).isEqualTo(판매자.getId());
		assertThat(상대(c, 방_C_to_B)).isEqualTo(구매자.getId());

		// 마지막 쪽지 내용이 방마다 맞다 (섞여 있으면 다른 방 쪽지가 나온다)
		for (JsonNode 항목 : c) {
			String 내용 = 항목.get("lastMessage").get("content").asText();
			long id = 항목.get("id").asLong();
			assertThat(내용).isEqualTo(id == 방_C_to_A ? "C가 A에게" : "C가 B에게");
			assertThat(항목.get("lastMessage").get("mine").asBoolean()).isTrue();
		}
		// 각 방 대화 보기에도 그 방 쪽지만 들어 있다
		JsonNode 상세 = json(보기(판매자쿠키, 방_C_to_A));
		assertThat(상세.get("messages")).hasSize(1);
		assertThat(상세.get("messages").get(0).get("content").asText()).isEqualTo("C가 A에게");
	}

	private List<Long> ids(JsonNode arr) {
		List<Long> r = new ArrayList<>();
		arr.forEach(n -> r.add(n.get("id").asLong()));
		return r;
	}

	private long 상대(JsonNode arr, long roomId) {
		for (JsonNode n : arr) {
			if (n.get("id").asLong() == roomId) {
				return n.get("partner").get("id").asLong();
			}
		}
		throw new AssertionError("목록에 방 없음: " + roomId);
	}
}
