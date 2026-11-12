package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.HashSet;
import java.util.Set;

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

// S14-a 보충 검사(ConversationStartApiTests가 안 다룬 것): 응답 모양·DB 값, 검사 순서 조합,
// 글 삭제/회원 탈퇴 뒤 대화방 보존, 보내는 도중 삭제/탈퇴가 끼는 경쟁
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationStartExtraTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired PlatformTransactionManager txManager;

	User 판매자, 구매자, 다른구매자, 고려대회원, 미인증회원;
	Cookie 판매자쿠키, 구매자쿠키, 다른구매자쿠키, 고려쿠키, 미인증쿠키;
	long 글id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "xseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "xbuyer", "연세대학교");
		다른구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "xbuyer2", "연세대학교");
		고려대회원 = TestLogin.인증된_사용자(userRepository, jdbc, "xkorea", "고려대학교");
		미인증회원 = TestLogin.사용자(userRepository, "xunverified");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		다른구매자쿠키 = TestLogin.쿠키(tokenService, 다른구매자);
		고려쿠키 = TestLogin.쿠키(tokenService, 고려대회원);
		미인증쿠키 = TestLogin.쿠키(tokenService, 미인증회원);
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

	// 기본 보내기: CSRF 포함, 본문 JSON 그대로
	private MockHttpServletResponse 보내기(Cookie cookie, Object itemId, String json) throws Exception {
		var req = post("/market-items/" + itemId + "/conversations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(json);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private static String 정상본문() {
		return "{\"content\":\"아직 있나요?\"}";
	}

	private MockHttpServletResponse CSRF없이(Cookie cookie, Object itemId, String json) throws Exception {
		var req = post("/market-items/" + itemId + "/conversations").contentType(MediaType.APPLICATION_JSON).content(json);
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private int 센다(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private void 상태바꾸기(String status) {
		jdbc.update("update market_items set status = ? where id = ?", status, 글id);
	}

	// ---------- 조건 2: 성공 응답 모양과 DB 값 ----------

	@Test
	void 조건2_응답_필드가_정확히_정해진_키뿐이고_DB_값이_맞다() throws Exception {
		MockHttpServletResponse res = 보내기(구매자쿠키, 글id, 정상본문());

		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode json = om.readTree(res.getContentAsString());
		Set<String> 최상위 = new HashSet<>(json.propertyNames());
		assertThat(최상위).containsExactlyInAnyOrder("conversationId", "message");
		Set<String> 쪽지키 = new HashSet<>(json.get("message").propertyNames());
		assertThat(쪽지키).as("엔티티가 아니라 DTO 키만").containsExactlyInAnyOrder("id", "content", "createdAt", "mine");

		long 방id = json.get("conversationId").asLong();
		assertThat(jdbc.queryForObject("select seller_id from conversations where id = ?", Long.class, 방id))
				.isEqualTo(판매자.getId());
		assertThat(jdbc.queryForObject("select buyer_id from conversations where id = ?", Long.class, 방id))
				.isEqualTo(구매자.getId());
		assertThat(센다("select count(*) from conversations")).isEqualTo(1);
		assertThat(센다("select count(*) from messages")).isEqualTo(1);
		assertThat(jdbc.queryForObject("select sender_id from messages where conversation_id = ?", Long.class, 방id))
				.as("보낸 사람은 구매자").isEqualTo(구매자.getId());
		assertThat(jdbc.queryForObject("select content from messages where conversation_id = ?", String.class, 방id))
				.isEqualTo("아직 있나요?");
	}

	@Test
	void 조건2_예약중_글에도_201() throws Exception {
		상태바꾸기("RESERVED");

		assertThat(보내기(구매자쿠키, 글id, 정상본문()).getStatus()).isEqualTo(201);
		assertThat(센다("select count(*) from conversations")).isEqualTo(1);
	}

	// ---------- 조건 3: 다른 구매자는 새 방 ----------

	@Test
	void 조건3_다른_구매자가_같은_글에_보내면_새_방이_생긴다() throws Exception {
		long 방1 = om.readTree(보내기(구매자쿠키, 글id, 정상본문()).getContentAsString()).get("conversationId").asLong();
		long 방2 = om.readTree(보내기(다른구매자쿠키, 글id, 정상본문()).getContentAsString()).get("conversationId").asLong();

		assertThat(방2).isNotEqualTo(방1);
		assertThat(센다("select count(*) from conversations where market_item_id = " + 글id)).isEqualTo(2);
		assertThat(센다("select count(*) from messages")).isEqualTo(2);
	}

	// ---------- 조건 5: 인증 관련 ----------

	@Test
	void 조건5_회원_행_없는_유령_토큰은_401이고_아무것도_저장되지_않는다() throws Exception {
		assertThat(보내기(TestLogin.행없는_회원_토큰(jwtEncoder), 글id, 정상본문()).getStatus()).isEqualTo(401);
		assertThat(센다("select count(*) from conversations")).isZero();
		assertThat(센다("select count(*) from messages")).isZero();
	}

	@Test
	void 조건5_CSRF_없으면_로그인한_정상_회원도_403이고_저장_없음() throws Exception {
		assertThat(CSRF없이(구매자쿠키, 글id, 정상본문()).getStatus()).isEqualTo(403);
		assertThat(센다("select count(*) from conversations")).isZero();
	}

	@Test
	void 조건5_다른_학교_인증_회원은_403이고_저장_없음() throws Exception {
		assertThat(보내기(고려쿠키, 글id, 정상본문()).getStatus()).isEqualTo(403);
		assertThat(센다("select count(*) from conversations")).isZero();
	}

	// ---------- 조건 7: 검사 순서 조합 ----------

	@Test
	void 조건7_CSRF가_없으면_비로그인이어도_403이다() throws Exception {
		assertThat(CSRF없이(null, 글id, 정상본문()).getStatus()).as("CSRF가 로그인보다 먼저").isEqualTo(403);
		assertThat(CSRF없이(TestLogin.행없는_회원_토큰(jwtEncoder), 글id, "{}").getStatus()).isEqualTo(403);
	}

	@Test
	void 조건7_비로그인이면_본문이_틀려도_401이다() throws Exception {
		assertThat(보내기(null, 글id, "{}").getStatus()).as("로그인이 본문 검사보다 먼저").isEqualTo(401);
		assertThat(보내기(null, 999999, "{\"content\":\"\"}").getStatus()).isEqualTo(401);
	}

	@Test
	void 조건7_본문이_틀리면_회원_없는_토큰이어도_400이다() throws Exception {
		assertThat(보내기(TestLogin.행없는_회원_토큰(jwtEncoder), 글id, "{\"content\":\"\"}").getStatus())
				.as("본문 400이 회원 없음 401보다 먼저").isEqualTo(400);
		assertThat(보내기(TestLogin.행없는_회원_토큰(jwtEncoder), 글id, "{}").getStatus()).isEqualTo(400);
	}

	@Test
	void 조건7_본문이_틀리면_미인증_회원이나_없는_글이어도_400이다() throws Exception {
		assertThat(보내기(미인증쿠키, 글id, "{\"content\":\"   \"}").getStatus()).isEqualTo(400);
		assertThat(보내기(구매자쿠키, 999999, "{\"content\":\"\"}").getStatus()).isEqualTo(400);
		assertThat(보내기(판매자쿠키, 글id, "{\"content\":\"\"}").getStatus()).as("자기 글보다 본문이 먼저").isEqualTo(400);
	}

	@Test
	void 조건7_본문이_정상이면_회원_없는_토큰은_없는_글이어도_401이다() throws Exception {
		assertThat(보내기(TestLogin.행없는_회원_토큰(jwtEncoder), 999999, 정상본문()).getStatus()).isEqualTo(401);
	}

	@Test
	void 조건7_미인증_회원이_없는_글에_보내면_403이다() throws Exception {
		assertThat(보내기(미인증쿠키, 999999, 정상본문()).getStatus()).as("미인증 403이 없는 글 404보다 먼저").isEqualTo(403);
	}

	@Test
	void 조건7_미인증_회원은_거래완료_글이어도_403이다() throws Exception {
		상태바꾸기("DONE");
		assertThat(보내기(미인증쿠키, 글id, 정상본문()).getStatus()).isEqualTo(403);
	}

	@Test
	void 조건7_다른_학교_회원이_거래완료_글에_보내면_403이다() throws Exception {
		상태바꾸기("DONE");
		assertThat(보내기(고려쿠키, 글id, 정상본문()).getStatus()).as("다른 학교 403이 DONE 409보다 먼저").isEqualTo(403);
		assertThat(센다("select count(*) from conversations")).isZero();
	}

	@Test
	void 조건7_자기_글이_거래완료여도_400이다() throws Exception {
		상태바꾸기("DONE");
		assertThat(보내기(판매자쿠키, 글id, 정상본문()).getStatus()).as("자기 글 400이 DONE 409보다 먼저").isEqualTo(400);
	}

	@Test
	void 조건7_다른_학교_회원은_없는_글이면_404다() throws Exception {
		// 없는 글은 학교를 알 수 없으니 404가 다른 학교 403보다 먼저
		assertThat(보내기(고려쿠키, 999999, 정상본문()).getStatus()).isEqualTo(404);
	}

	// ---------- 조건 8: 글·회원이 지워져도 대화는 남는다 ----------

	@Test
	void 조건8_글을_삭제하면_대화방은_남고_글_칸만_NULL_제목과_쪽지는_유지된다() throws Exception {
		long 방id = om.readTree(보내기(구매자쿠키, 글id, 정상본문()).getContentAsString()).get("conversationId").asLong();

		var res = mockMvc.perform(delete("/market-items/" + 글id).with(csrf()).cookie(판매자쿠키)).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(204);

		assertThat(센다("select count(*) from conversations where id = " + 방id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select market_item_id from conversations where id = ?", Long.class, 방id))
				.isNull();
		assertThat(jdbc.queryForObject("select item_title from conversations where id = ?", String.class, 방id))
				.isEqualTo("자전거");
		assertThat(센다("select count(*) from messages where conversation_id = " + 방id)).isEqualTo(1);
	}

	@Test
	void 조건8_구매자가_탈퇴하면_buyer와_sender가_NULL이고_행은_남는다() throws Exception {
		long 방id = om.readTree(보내기(구매자쿠키, 글id, 정상본문()).getContentAsString()).get("conversationId").asLong();

		// 테스트 회원의 비밀번호는 "{noop}unused-password"
		var res = mockMvc.perform(delete("/users/me").with(csrf()).cookie(구매자쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse();
		assertThat(res.getStatus()).as("탈퇴 API").isEqualTo(204);

		assertThat(센다("select count(*) from conversations where id = " + 방id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select buyer_id from conversations where id = ?", Long.class, 방id)).isNull();
		assertThat(jdbc.queryForObject("select seller_id from conversations where id = ?", Long.class, 방id))
				.isEqualTo(판매자.getId());
		assertThat(센다("select count(*) from messages where conversation_id = " + 방id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select sender_id from messages where conversation_id = ?", Long.class, 방id))
				.isNull();
	}

	@Test
	void 조건8_판매자가_탈퇴하면_글은_사라지고_대화방과_쪽지는_남는다() throws Exception {
		long 방id = om.readTree(보내기(구매자쿠키, 글id, 정상본문()).getContentAsString()).get("conversationId").asLong();

		var res = mockMvc.perform(delete("/users/me").with(csrf()).cookie(판매자쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse();
		assertThat(res.getStatus()).as("탈퇴 API").isEqualTo(204);

		assertThat(센다("select count(*) from market_items where id = " + 글id)).as("글은 CASCADE로 삭제").isZero();
		assertThat(센다("select count(*) from conversations where id = " + 방id)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select market_item_id from conversations where id = ?", Long.class, 방id))
				.isNull();
		assertThat(jdbc.queryForObject("select seller_id from conversations where id = ?", Long.class, 방id)).isNull();
		assertThat(jdbc.queryForObject("select buyer_id from conversations where id = ?", Long.class, 방id))
				.isEqualTo(구매자.getId());
		assertThat(센다("select count(*) from messages where conversation_id = " + 방id)).isEqualTo(1);
	}

	@Test
	void 조건8_글이_지워진_뒤_구매자_탈퇴가_이어져도_행이_남는다() throws Exception {
		long 방id = om.readTree(보내기(구매자쿠키, 글id, 정상본문()).getContentAsString()).get("conversationId").asLong();
		jdbc.update("delete from market_items where id = ?", 글id);
		jdbc.update("delete from users where id = ?", 구매자.getId());

		assertThat(센다("select count(*) from conversations where id = " + 방id)).isEqualTo(1);
		assertThat(센다("select count(*) from conversations where id = " + 방id
				+ " and market_item_id is null and buyer_id is null")).isEqualTo(1);
		assertThat(센다("select count(*) from messages where conversation_id = " + 방id + " and sender_id is null"))
				.isEqualTo(1);
	}

	// ---------- 조건 9: 보내는 도중 삭제/탈퇴가 끼는 경쟁 (500이면 안 된다) ----------

	@Test
	void 조건9_방_만들기_직전에_글_삭제가_커밋되면_404이고_저장_없음() throws Exception {
		long id = 글id;
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from market_items where id = ?", id),
				() -> {
					try {
						return 보내기(구매자쿠키, id, 정상본문()).getStatus();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				},
				() -> {
				});

		assertThat(status).isEqualTo(404);
		assertThat(센다("select count(*) from conversations")).isZero();
		assertThat(센다("select count(*) from messages")).isZero();
	}

	@Test
	void 조건9_방_만들기_직전에_구매자_탈퇴가_커밋되면_401이고_저장_없음() throws Exception {
		long uid = 구매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from users where id = ?", uid),
				() -> {
					try {
						return 보내기(구매자쿠키, 글id, 정상본문()).getStatus();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				},
				() -> {
				});

		assertThat(status).isEqualTo(401);
		assertThat(센다("select count(*) from conversations")).isZero();
		assertThat(센다("select count(*) from messages")).isZero();
	}

	@Test
	void 조건9_기존_방에_붙이기_직전에_구매자_탈퇴가_커밋되면_401이고_쪽지가_늘지_않는다() throws Exception {
		보내기(구매자쿠키, 글id, 정상본문()); // 방과 쪽지 1개를 먼저 만든다
		long uid = 구매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from users where id = ?", uid),
				() -> {
					try {
						return 보내기(구매자쿠키, 글id, "{\"content\":\"둘째\"}").getStatus();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				},
				() -> {
				});

		assertThat(status).isEqualTo(401);
		assertThat(센다("select count(*) from conversations")).isEqualTo(1);
		assertThat(센다("select count(*) from messages")).isEqualTo(1);
	}

	@Test
	void 조건9_기존_방에_붙이는_도중_글_삭제가_커밋돼도_201이다() throws Exception {
		보내기(구매자쿠키, 글id, 정상본문());
		long id = 글id;
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.update("delete from market_items where id = ?", id),
				() -> {
					try {
						return 보내기(구매자쿠키, id, "{\"content\":\"둘째\"}").getStatus();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				},
				() -> {
				});

		// 요청이 글·방을 읽은 시점엔 삭제가 아직 커밋 전이라 방이 보인다 -> 쪽지는 방 id에 붙고, 방은 글 삭제로 market_item_id만 NULL이 된다
		assertThat(status).as("이미 방이 있어 이어 붙임 201").isEqualTo(201);
		assertThat(센다("select count(*) from conversations")).isEqualTo(1);
		assertThat(센다("select count(*) from conversations where market_item_id is null")).as("글 칸은 SET NULL").isEqualTo(1);
		assertThat(센다("select count(*) from messages")).as("첫 쪽지 + 둘째 쪽지").isEqualTo(2);
	}
}
