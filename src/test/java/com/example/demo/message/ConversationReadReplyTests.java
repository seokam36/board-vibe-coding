package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S14-b 기본 검사: 대화 목록(GET /conversations), 대화 보기(GET /conversations/{id}), 답장(POST /conversations/{id}/messages)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationReadReplyTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired EntityManagerFactory emf;

	User 판매자, 판매자2, 구매자, 제삼자, 관리자;
	Cookie 판매자쿠키, 판매자2쿠키, 구매자쿠키, 제삼자쿠키, 관리자쿠키;
	long 글id; // 판매자의 글 "자전거"

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "rseller", "연세대학교");
		판매자2 = TestLogin.인증된_사용자(userRepository, jdbc, "rseller2", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "rbuyer", "연세대학교");
		제삼자 = TestLogin.인증된_사용자(userRepository, jdbc, "rthird", "연세대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "radmin");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		판매자2쿠키 = TestLogin.쿠키(tokenService, 판매자2);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		제삼자쿠키 = TestLogin.쿠키(tokenService, 제삼자);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
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

	// 첫 쪽지를 보내 방을 만들고 방 id를 돌려준다
	private long 방_만들기(Cookie 보내는사람, long itemId, String content) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post("/market-items/" + itemId + "/conversations").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}").cookie(보내는사람))
				.andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 첫 쪽지").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("conversationId").asLong();
	}

	private MockHttpServletResponse 답장(Cookie cookie, Object roomId, String content) throws Exception {
		var req = post("/conversations/" + roomId + "/messages").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"" + content + "\"}");
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 목록(Cookie cookie) throws Exception {
		var req = get("/conversations");
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 보기(Cookie cookie, Object roomId) throws Exception {
		var req = get("/conversations/" + roomId);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private JsonNode json(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	// ---------- 목록 ----------

	@Test
	void 목록_방이_없으면_빈_배열이다() throws Exception {
		MockHttpServletResponse r = 목록(구매자쿠키);
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(json(r).isArray()).isTrue();
		assertThat(json(r)).isEmpty();
	}

	@Test
	void 목록_내가_낀_방만_나오고_최근_쪽지_순이며_답장하면_맨_위로_올라간다() throws Exception {
		long 글2 = 중고글_올리기(판매자2쿠키, "책상");
		long 방1 = 방_만들기(구매자쿠키, 글id, "자전거 문의");
		long 방2 = 방_만들기(구매자쿠키, 글2, "책상 문의");
		long 남의방 = 방_만들기(제삼자쿠키, 글id, "제삼자 문의");

		JsonNode 구매자목록 = json(목록(구매자쿠키));
		assertThat(구매자목록).hasSize(2);
		assertThat(구매자목록.get(0).get("id").asLong()).as("나중에 만든 방이 위").isEqualTo(방2);
		assertThat(구매자목록.get(1).get("id").asLong()).isEqualTo(방1);

		// 판매자는 자기 글의 방 2개(구매자, 제삼자)만 본다
		JsonNode 판매자목록 = json(목록(판매자쿠키));
		assertThat(판매자목록).hasSize(2);
		assertThat(판매자목록.get(0).get("id").asLong()).isEqualTo(남의방);
		assertThat(판매자목록.get(1).get("id").asLong()).isEqualTo(방1);

		// 방1에 답장하면 구매자 목록에서 방1이 맨 위, 판매자 목록에서도 맨 위
		assertThat(답장(판매자쿠키, 방1, "있어요").getStatus()).isEqualTo(201);
		assertThat(json(목록(구매자쿠키)).get(0).get("id").asLong()).isEqualTo(방1);
		assertThat(json(목록(판매자쿠키)).get(0).get("id").asLong()).isEqualTo(방1);
		// 제삼자 목록은 그대로 1개
		assertThat(json(목록(제삼자쿠키))).hasSize(1);
	}

	@Test
	void 목록_마지막_시각이_같으면_id_내림차순이다() throws Exception {
		long 글2 = 중고글_올리기(판매자2쿠키, "책상");
		long 방1 = 방_만들기(구매자쿠키, 글id, "하나");
		long 방2 = 방_만들기(구매자쿠키, 글2, "둘");
		jdbc.update("update conversations set last_message_at = '2026-01-01T00:00:00Z'");

		JsonNode 목록 = json(목록(구매자쿠키));
		assertThat(목록.get(0).get("id").asLong()).isEqualTo(방2);
		assertThat(목록.get(1).get("id").asLong()).isEqualTo(방1);
	}

	@Test
	void 목록_항목_모양_글_상대_마지막_쪽지와_mine() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "첫째");
		답장(판매자쿠키, 방, "마지막 답");

		JsonNode 구매자쪽 = json(목록(구매자쿠키)).get(0);
		assertThat(구매자쪽.get("item").get("id").asLong()).isEqualTo(글id);
		assertThat(구매자쪽.get("item").get("title").asText()).isEqualTo("자전거");
		assertThat(구매자쪽.get("item").get("deleted").asBoolean()).isFalse();
		assertThat(구매자쪽.get("partner").get("id").asLong()).isEqualTo(판매자.getId());
		assertThat(구매자쪽.get("partner").get("nickname").asText()).isEqualTo("rseller");
		assertThat(구매자쪽.get("lastMessage").get("content").asText()).isEqualTo("마지막 답");
		assertThat(구매자쪽.get("lastMessage").get("mine").asBoolean()).as("마지막은 판매자가 보냄").isFalse();
		assertThat(구매자쪽.get("lastMessage").get("createdAt").asText()).isNotBlank();

		JsonNode 판매자쪽 = json(목록(판매자쿠키)).get(0);
		assertThat(판매자쪽.get("partner").get("id").asLong()).isEqualTo(구매자.getId());
		assertThat(판매자쪽.get("lastMessage").get("mine").asBoolean()).isTrue();
	}

	@Test
	void 목록_글이_지워진_방은_deleted_true이고_제목은_복사본이다() throws Exception {
		방_만들기(구매자쿠키, 글id, "문의");
		jdbc.update("delete from market_items where id = ?", 글id);

		JsonNode item = json(목록(구매자쿠키)).get(0).get("item");
		assertThat(item.get("id").isNull()).isTrue();
		assertThat(item.get("deleted").asBoolean()).isTrue();
		assertThat(item.get("title").asText()).isEqualTo("자전거");
	}

	@Test
	void 목록_상대가_탈퇴했으면_partner는_null이고_보낸_사람이_탈퇴한_마지막_쪽지는_mine_false() throws Exception {
		방_만들기(구매자쿠키, 글id, "문의");
		// 구매자(쪽지를 보낸 사람)가 탈퇴한 상황: DB가 buyer_id·sender_id를 NULL로 비운다. 판매자 입장에서 본다.
		jdbc.update("delete from users where id = ?", 구매자.getId());

		JsonNode 항목 = json(목록(판매자쿠키)).get(0);
		assertThat(항목.get("partner").isNull()).isTrue();
		assertThat(항목.get("lastMessage").get("content").asText()).isEqualTo("문의");
		assertThat(항목.get("lastMessage").get("mine").asBoolean()).isFalse();
	}

	@Test
	void 목록_쿼리_수는_방_개수와_무관하다() throws Exception {
		// 무엇: 방 1개 / 6개일 때 SQL 수 비교. 왜: 상대 닉네임·마지막 쪽지를 방마다 따로 읽는 N+1이 없는지 확인
		방_만들기(구매자쿠키, 글id, "하나");
		long 하나일때 = 목록_쿼리수();
		for (int i = 0; i < 5; i++) {
			User 새판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "nplus" + i, "연세대학교");
			long 새글 = 중고글_올리기(TestLogin.쿠키(tokenService, 새판매자), "물건" + i);
			long 방 = 방_만들기(구매자쿠키, 새글, "문의" + i);
			답장(TestLogin.쿠키(tokenService, 새판매자), 방, "답" + i);
		}
		long 여섯일때 = 목록_쿼리수();
		assertThat(여섯일때).as("방 1개 vs 6개 쿼리 수").isEqualTo(하나일때);
		assertThat(하나일때).as("회원 1 + 방 1 + 마지막 쪽지 1 + 안 읽은 수 1").isLessThanOrEqualTo(4);
	}

	private long 목록_쿼리수() throws Exception {
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			assertThat(json(목록(구매자쿠키))).isNotEmpty();
			return stats.getPrepareStatementCount();
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	// ---------- 대화 보기 ----------

	@Test
	void 보기_참가자는_200이고_쪽지가_id_오름차순이며_mine이_사람마다_다르다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "첫째");
		답장(판매자쿠키, 방, "둘째");
		답장(구매자쿠키, 방, "셋째");
		// created_at 순서를 일부러 뒤집어도 id 순서로 나와야 한다
		jdbc.update("update messages set created_at = created_at - (id * interval '1 hour')");

		MockHttpServletResponse r = 보기(구매자쿠키, 방);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode j = json(r);
		assertThat(j.get("id").asLong()).isEqualTo(방);
		assertThat(j.get("item").get("title").asText()).isEqualTo("자전거");
		assertThat(j.get("partner").get("nickname").asText()).isEqualTo("rseller");
		JsonNode m = j.get("messages");
		assertThat(m).hasSize(3);
		assertThat(m.get(0).get("content").asText()).isEqualTo("첫째");
		assertThat(m.get(1).get("content").asText()).isEqualTo("둘째");
		assertThat(m.get(2).get("content").asText()).isEqualTo("셋째");
		assertThat(m.get(0).get("mine").asBoolean()).isTrue();
		assertThat(m.get(1).get("mine").asBoolean()).isFalse();
		assertThat(m.get(2).get("mine").asBoolean()).isTrue();

		JsonNode 판매자가본 = json(보기(판매자쿠키, 방)).get("messages");
		assertThat(판매자가본.get(0).get("mine").asBoolean()).isFalse();
		assertThat(판매자가본.get(1).get("mine").asBoolean()).isTrue();
	}

	@Test
	void 보기_남의_방과_관리자와_없는_방은_404이고_숫자가_아닌_id는_400이다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		assertThat(보기(제삼자쿠키, 방).getStatus()).isEqualTo(404);
		assertThat(보기(관리자쿠키, 방).getStatus()).as("관리자도 남의 대화는 404").isEqualTo(404);
		assertThat(보기(구매자쿠키, 999999999L).getStatus()).isEqualTo(404);
		assertThat(보기(구매자쿠키, "abc").getStatus()).isEqualTo(400);
	}

	@Test
	void 보기_상대가_탈퇴해도_200이고_partner는_null이다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		jdbc.update("delete from users where id = ?", 판매자.getId()); // 글도 CASCADE로 같이 지워진다

		MockHttpServletResponse r = 보기(구매자쿠키, 방);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode j = json(r);
		assertThat(j.get("partner").isNull()).isTrue();
		assertThat(j.get("item").get("deleted").asBoolean()).isTrue();
		assertThat(j.get("messages")).hasSize(1);
		assertThat(j.get("messages").get(0).get("mine").asBoolean()).isTrue();
	}

	// 무엇: 판매자가 답장한 뒤 탈퇴하면, 구매자가 봐도 쪽지 2통이 다 나오고 판매자 것(보낸 사람 NULL)은 mine=false.
	// 왜: MessageRepository.findResponses의 `m.sender.id = :me`가 inner join으로 바뀌면 보낸 사람이 NULL인 쪽지가 목록에서 사라진다. 그걸 잡는다.
	@Test
	void 보기_보낸_사람이_탈퇴한_쪽지도_남고_mine_false다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		assertThat(답장(판매자쿠키, 방, "답장이에요").getStatus()).isEqualTo(201);
		jdbc.update("delete from users where id = ?", 판매자.getId()); // 판매자 탈퇴: 방의 seller_id·쪽지의 sender_id가 NULL

		MockHttpServletResponse r = 보기(구매자쿠키, 방);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode m = json(r).get("messages");
		assertThat(m).as("보낸 사람이 탈퇴한 쪽지도 사라지지 않는다").hasSize(2);
		assertThat(m.get(0).get("content").asText()).isEqualTo("문의");
		assertThat(m.get(0).get("mine").asBoolean()).isTrue();
		assertThat(m.get(1).get("content").asText()).isEqualTo("답장이에요");
		assertThat(m.get(1).get("mine").asBoolean()).as("탈퇴한 판매자의 쪽지는 내 것이 아니다").isFalse();
	}

	@Test
	void 보기_학교_인증이_풀린_회원도_자기_대화는_읽을_수_있다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		jdbc.update("update users set verified_school_id = null where id = ?", 구매자.getId());
		assertThat(보기(구매자쿠키, 방).getStatus()).isEqualTo(200);
		assertThat(목록(구매자쿠키).getStatus()).isEqualTo(200);
		assertThat(답장(구매자쿠키, 방, "다시").getStatus()).as("보내기는 학교 인증 필요").isEqualTo(403);
	}

	// ---------- 답장 ----------

	@Test
	void 답장_201이고_본문_모양이_맞고_저장된다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "첫째");
		MockHttpServletResponse r = 답장(판매자쿠키, 방, "있어요");

		assertThat(r.getStatus()).isEqualTo(201);
		JsonNode j = json(r);
		assertThat(j.get("id").asLong()).isPositive();
		assertThat(j.get("content").asText()).isEqualTo("있어요");
		assertThat(j.get("mine").asBoolean()).isTrue();
		assertThat(j.get("createdAt").asText()).isNotBlank();
		assertThat(jdbc.queryForObject("select count(*) from messages where conversation_id = ? and sender_id = ?",
				Integer.class, 방, 판매자.getId())).isEqualTo(1);
		// 방의 마지막 쪽지 시각이 새 쪽지 시각과 같다
		assertThat(jdbc.queryForObject("select last_message_at = (select max(created_at) from messages where conversation_id = ?) "
				+ "from conversations where id = ?", Boolean.class, 방, 방)).isTrue();
	}

	@Test
	void 답장_남의_방과_관리자와_없는_방은_404다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		assertThat(답장(제삼자쿠키, 방, "끼어들기").getStatus()).isEqualTo(404);
		// 학교 인증 전 관리자는 방 검사보다 먼저 403(검사 순서: 미인증 403 -> 방 404). 인증한 관리자는 남의 방이면 404.
		assertThat(답장(관리자쿠키, 방, "관리자").getStatus()).isEqualTo(403);
		TestLogin.학교_인증_처리(jdbc, "radmin", "연세대학교");
		assertThat(답장(관리자쿠키, 방, "관리자").getStatus()).as("인증한 관리자도 남의 방은 404").isEqualTo(404);
		assertThat(답장(구매자쿠키, 999999999L, "없는 방").getStatus()).isEqualTo(404);
		assertThat(답장(구매자쿠키, "abc", "문자").getStatus()).isEqualTo(400);
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).isEqualTo(1);
	}

	@Test
	void 답장_상대가_탈퇴했으면_409이고_쪽지는_저장되지_않는다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		jdbc.update("delete from users where id = ?", 판매자.getId());
		assertThat(답장(구매자쿠키, 방, "계세요?").getStatus()).isEqualTo(409);
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).isEqualTo(1);

		// 구매자가 탈퇴한 방에 판매자가 보내는 경우도 409
		long 글2 = 중고글_올리기(판매자2쿠키, "책상");
		long 방2 = 방_만들기(제삼자쿠키, 글2, "문의");
		jdbc.update("delete from users where id = ?", 제삼자.getId());
		assertThat(답장(판매자2쿠키, 방2, "네?").getStatus()).isEqualTo(409);
	}

	@Test
	void 답장_글이_지워진_방과_거래완료_글의_방에도_201이다() throws Exception {
		long 방1 = 방_만들기(구매자쿠키, 글id, "문의");
		long 글2 = 중고글_올리기(판매자2쿠키, "책상");
		long 방2 = 방_만들기(구매자쿠키, 글2, "문의2");

		jdbc.update("delete from market_items where id = ?", 글id);
		jdbc.update("update market_items set status = 'DONE' where id = ?", 글2);

		assertThat(답장(구매자쿠키, 방1, "글이 지워졌어도").getStatus()).isEqualTo(201);
		assertThat(답장(판매자2쿠키, 방2, "거래 끝났어도").getStatus()).isEqualTo(201);
	}

	@Test
	void 답장_검사_순서_CSRF_401_400_유령토큰_401_미인증_403() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");

		// CSRF 없음 -> 403 (로그인돼 있어도)
		assertThat(mockMvc.perform(post("/conversations/" + 방 + "/messages").contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"x\"}").cookie(구매자쿠키)).andReturn().getResponse().getStatus()).isEqualTo(403);
		// 비로그인 -> 401
		assertThat(답장(null, 방, "x").getStatus()).isEqualTo(401);
		// 본문 오류 -> 400 (공백뿐, 1001자)
		assertThat(답장(구매자쿠키, 방, "   ").getStatus()).isEqualTo(400);
		assertThat(답장(구매자쿠키, 방, "가".repeat(1001)).getStatus()).isEqualTo(400);
		// 본문이 틀리면 남의 방이어도 404보다 400이 먼저
		assertThat(답장(제삼자쿠키, 방, "   ").getStatus()).isEqualTo(400);
		// 회원 행이 없는 토큰 -> 401
		assertThat(답장(TestLogin.행없는_회원_토큰(jwtEncoder), 방, "x").getStatus()).isEqualTo(401);
		// 미인증 회원 -> 403 (방 검사보다 먼저)
		User 미인증 = TestLogin.사용자(userRepository, "runverified");
		assertThat(답장(TestLogin.쿠키(tokenService, 미인증), 방, "x").getStatus()).isEqualTo(403);
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).isEqualTo(1);
	}

	// ---------- 세 API 공통: 로그인 필요 ----------

	@Test
	void 세_API_모두_비로그인과_유령_토큰은_401이다() throws Exception {
		long 방 = 방_만들기(구매자쿠키, 글id, "문의");
		Cookie 유령 = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(목록(null).getStatus()).isEqualTo(401);
		assertThat(보기(null, 방).getStatus()).isEqualTo(401);
		assertThat(답장(null, 방, "x").getStatus()).isEqualTo(401);
		assertThat(목록(유령).getStatus()).isEqualTo(401);
		assertThat(보기(유령, 방).getStatus()).isEqualTo(401);
		assertThat(답장(유령, 방, "x").getStatus()).isEqualTo(401);
	}
}
