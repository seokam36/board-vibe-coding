package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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

// S14-a 첫 쪽지 보내기 기본 검사: 성공 201, 재전송은 같은 방, 거래완료 409, 자기 글 400 등
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationStartApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;

	User 판매자;
	User 구매자;
	User 고려대회원;
	User 미인증회원;
	Cookie 판매자쿠키, 구매자쿠키, 고려쿠키, 미인증쿠키;
	long 글id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "cseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "cbuyer", "연세대학교");
		고려대회원 = TestLogin.인증된_사용자(userRepository, jdbc, "ckorea", "고려대학교");
		미인증회원 = TestLogin.사용자(userRepository, "cunverified");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		고려쿠키 = TestLogin.쿠키(tokenService, 고려대회원);
		미인증쿠키 = TestLogin.쿠키(tokenService, 미인증회원);
		글id = 중고글_올리기(판매자쿠키, "자전거");
	}

	// 쪽지는 방이 지워지면 CASCADE로, 방은 글·회원이 지워지면 SET NULL로 처리되지만 깔끔하게 방부터 지운다
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

	private MockHttpServletResponse 보내기(Cookie cookie, Object itemId, String content) throws Exception {
		var req = post("/market-items/" + itemId + "/conversations").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"" + content + "\"}");
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private int 센다(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	// ---------- 테스트 ----------

	@Test
	void 첫_쪽지는_201이고_방과_쪽지가_저장된다() throws Exception {
		MockHttpServletResponse res = 보내기(구매자쿠키, 글id, "안녕하세요");

		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(res.getHeader("Location")).as("단건 GET이 없어 Location 없음").isNull();
		JsonNode json = om.readTree(res.getContentAsString());
		assertThat(json.get("conversationId").asLong()).isPositive();
		assertThat(json.get("message").get("content").asText()).isEqualTo("안녕하세요");
		assertThat(json.get("message").get("mine").asBoolean()).isTrue();
		assertThat(json.get("message").get("id").asLong()).isPositive();
		assertThat(json.get("message").get("createdAt").asText()).isNotBlank();

		// 방에는 글 제목이 복사되고 구매자·판매자가 채워진다
		assertThat(jdbc.queryForObject("select item_title from conversations where id = ?", String.class,
				json.get("conversationId").asLong())).isEqualTo("자전거");
		assertThat(센다("select count(*) from conversations where buyer_id = " + 구매자.getId()
				+ " and seller_id = " + 판매자.getId() + " and market_item_id = " + 글id)).isEqualTo(1);
		assertThat(센다("select count(*) from messages")).isEqualTo(1);
	}

	@Test
	void 같은_글에_다시_보내면_같은_방에_쪽지가_붙고_마지막_시각이_갱신된다() throws Exception {
		JsonNode 첫째 = om.readTree(보내기(구매자쿠키, 글id, "첫째").getContentAsString());
		long 방id = 첫째.get("conversationId").asLong();
		// 연속 두 요청이 같은 시계 눈금에 찍히면 "더 늦을 때만 갱신"이라 시각이 안 바뀐다(Windows에서 불안정).
		// 그래서 첫 쪽지와 방의 마지막 시각을 1시간 전으로 돌려 놓고 두 번째를 보낸다(sleep 없이 확실히 "뒤"가 됨).
		jdbc.update("update messages set created_at = created_at - interval '1 hour' where conversation_id = ?", 방id);
		jdbc.update("update conversations set last_message_at = last_message_at - interval '1 hour' where id = ?", 방id);
		var 처음시각 = jdbc.queryForObject("select last_message_at from conversations where id = ?",
				java.sql.Timestamp.class, 방id);

		MockHttpServletResponse res = 보내기(구매자쿠키, 글id, "둘째");

		assertThat(res.getStatus()).isEqualTo(201);
		JsonNode 둘째 = om.readTree(res.getContentAsString());
		assertThat(둘째.get("conversationId").asLong()).isEqualTo(방id);
		assertThat(둘째.get("message").get("id").asLong()).isNotEqualTo(첫째.get("message").get("id").asLong());
		assertThat(센다("select count(*) from conversations")).isEqualTo(1);
		assertThat(센다("select count(*) from messages where conversation_id = " + 방id)).isEqualTo(2);
		var 나중시각 = jdbc.queryForObject("select last_message_at from conversations where id = ?",
				java.sql.Timestamp.class, 방id);
		assertThat(나중시각).isAfter(처음시각);
		// 방의 마지막 시각은 두 번째 쪽지가 만들어진 시각과 같다
		var 둘째쪽지시각 = jdbc.queryForObject("select created_at from messages where id = ?",
				java.sql.Timestamp.class, 둘째.get("message").get("id").asLong());
		assertThat(나중시각).isEqualTo(둘째쪽지시각);
	}

	@Test
	void 거래완료_글에_새_대화는_409() throws Exception {
		jdbc.update("update market_items set status = 'DONE' where id = ?", 글id);

		assertThat(보내기(구매자쿠키, 글id, "아직 있나요").getStatus()).isEqualTo(409);
		assertThat(센다("select count(*) from conversations")).isZero();
	}

	@Test
	void 이미_방이_있으면_거래완료_글이어도_201() throws Exception {
		long 방id = om.readTree(보내기(구매자쿠키, 글id, "첫째").getContentAsString()).get("conversationId").asLong();
		jdbc.update("update market_items set status = 'DONE' where id = ?", 글id);

		MockHttpServletResponse res = 보내기(구매자쿠키, 글id, "감사합니다");

		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(om.readTree(res.getContentAsString()).get("conversationId").asLong()).isEqualTo(방id);
	}

	@Test
	void 자기_글에는_400() throws Exception {
		assertThat(보내기(판매자쿠키, 글id, "내 글").getStatus()).isEqualTo(400);
		assertThat(센다("select count(*) from conversations")).isZero();
	}

	@Test
	void 없는_글은_404_다른_학교는_403_미인증은_403_로그인_없으면_401() throws Exception {
		assertThat(보내기(구매자쿠키, 999999, "안녕").getStatus()).isEqualTo(404);
		assertThat(보내기(고려쿠키, 글id, "안녕").getStatus()).isEqualTo(403);
		assertThat(보내기(미인증쿠키, 글id, "안녕").getStatus()).isEqualTo(403);
		assertThat(보내기(null, 글id, "안녕").getStatus()).isEqualTo(401);
	}

	@Test
	void CSRF_토큰이_없으면_403() throws Exception {
		MockHttpServletResponse res = mockMvc.perform(post("/market-items/" + 글id + "/conversations")
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"안녕\"}").cookie(구매자쿠키))
				.andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(403);
	}

	@Test
	void 본문이_틀리면_400() throws Exception {
		assertThat(보내기(구매자쿠키, 글id, "").getStatus()).as("빈 값").isEqualTo(400);
		assertThat(보내기(구매자쿠키, 글id, "   ").getStatus()).as("공백뿐").isEqualTo(400);
		assertThat(보내기(구매자쿠키, 글id, "가".repeat(1001)).getStatus()).as("1001자").isEqualTo(400);
		assertThat(보내기(구매자쿠키, 글id, "a\\u0000b").getStatus()).as("NUL 포함").isEqualTo(400);
		assertThat(보내기(구매자쿠키, "abc", "안녕").getStatus()).as("id가 숫자 아님").isEqualTo(400);
		MockHttpServletResponse 본문없음 = mockMvc.perform(post("/market-items/" + 글id + "/conversations")
				.with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}").cookie(구매자쿠키)).andReturn()
				.getResponse();
		assertThat(본문없음.getStatus()).as("content 없음").isEqualTo(400);
		assertThat(보내기(구매자쿠키, 글id, "가".repeat(1000)).getStatus()).as("1000자는 성공").isEqualTo(201);
	}

	// 결정적 재현: 다른 트랜잭션이 같은 (글, 구매자) 방을 INSERT만 해 두고 커밋 전이면, 요청의 방 INSERT는 UNIQUE에서 기다린다.
	// 커밋되는 순간 ON CONFLICT DO NOTHING이 "이미 있음"으로 끝나고(트랜잭션이 안 깨짐), 요청은 그 방을 다시 찾아 쪽지를 붙인다.
	// (일반 INSERT였다면 여기서 UNIQUE 위반 -> 트랜잭션이 깨져 500)
	@Test
	void 방이_커밋_직전_동시에_만들어지면_요청은_그_방에_쪽지를_붙여_201이다() throws Exception {
		long[] 먼저만든방 = new long[1];
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> 먼저만든방[0] = jdbc.queryForObject("insert into conversations "
						+ "(market_item_id, item_title, buyer_id, seller_id, created_at, last_message_at) "
						+ "values (?, '자전거', ?, ?, now(), now()) returning id", Long.class,
						글id, 구매자.getId(), 판매자.getId()),
				() -> {
					try {
						MockHttpServletResponse res = 보내기(구매자쿠키, 글id, "동시에");
						// 응답의 방 번호는 먼저 만들어진 방이어야 한다 (요청 스레드 안에서 확인해 돌려준다)
						assertThat(om.readTree(res.getContentAsString()).get("conversationId").asLong())
								.isEqualTo(먼저만든방[0]);
						return res.getStatus();
					} catch (Exception e) {
						throw new IllegalStateException(e);
					}
				},
				() -> {
				}); // 비워 둔다: 콜백이 끝나 잠금 트랜잭션이 커밋되면 요청이 깨어난다

		assertThat(status).isEqualTo(201);
		assertThat(센다("select count(*) from conversations")).as("방은 하나뿐").isEqualTo(1);
		assertThat(센다("select count(*) from messages where conversation_id = " + 먼저만든방[0]))
				.as("jdbc로 넣은 방에는 쪽지가 없었으니 요청의 쪽지 1개뿐").isEqualTo(1);
		assertThat(센다("select count(*) from messages")).isEqualTo(1);
	}
}
