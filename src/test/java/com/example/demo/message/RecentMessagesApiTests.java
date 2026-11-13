package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S14-g: 대화 보기 두 API(GET /conversations/{id}, GET /admin/reports/{id}/conversation)가
// 쪽지를 최근 200통만 돌려주고 hasMore를 알려주는지 검사한다.
// 대량 쪽지는 API 대신 SQL(generate_series)로 한 번에 넣는다. 방에는 첫 쪽지("첫째")가 이미 1통 있다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class RecentMessagesApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;

	User 판매자, 구매자, 관리자;
	Cookie 판매자쿠키, 구매자쿠키, 관리자쿠키;
	long 방id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "rmseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "rmbuyer", "연세대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "rmadmin");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		String body = "{\"title\":\"대량\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글").isEqualTo(201);
		long 글id = om.readTree(r.getContentAsString()).get("id").asLong();
		MockHttpServletResponse s = mockMvc.perform(post("/market-items/" + 글id + "/conversations").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"첫째\"}").cookie(구매자쿠키)).andReturn().getResponse();
		assertThat(s.getStatus()).as("준비용 첫 쪽지").isEqualTo(201);
		방id = om.readTree(s.getContentAsString()).get("conversationId").asLong();
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	// 방의 총 쪽지 수가 total이 되도록 판매자 쪽지를 채운다(구매자 입장에서는 안 읽은 쪽지). 내용은 m2..m{total}.
	private void 총_통수로_맞추기(int total) {
		jdbc.update("insert into messages (conversation_id, sender_id, content, created_at) "
				+ "select ?, ?, 'm' || g, now() from generate_series(2, ?) g", 방id, 판매자.getId(), total);
	}

	private JsonNode 본인_보기() throws Exception {
		MockHttpServletResponse res = mockMvc.perform(get("/conversations/" + 방id).cookie(구매자쿠키)).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		return om.readTree(res.getContentAsString());
	}

	private JsonNode 관리자_보기() throws Exception {
		MockHttpServletResponse rep = mockMvc.perform(post("/conversations/" + 방id + "/reports").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABUSE\"}").cookie(구매자쿠키)).andReturn().getResponse();
		assertThat(rep.getStatus()).as("준비용 신고").isIn(201, 409);
		long reportId = jdbc.queryForObject("select max(id) from reports where conversation_id = ?", Long.class, 방id);
		MockHttpServletResponse res = mockMvc.perform(get("/admin/reports/" + reportId + "/conversation").cookie(관리자쿠키)).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		return om.readTree(res.getContentAsString());
	}

	private long 마지막_id(JsonNode messages) {
		return messages.get(messages.size() - 1).get("id").asLong();
	}

	private void 오름차순_확인(JsonNode messages) {
		for (int i = 1; i < messages.size(); i++) {
			assertThat(messages.get(i).get("id").asLong()).isGreaterThan(messages.get(i - 1).get("id").asLong());
		}
	}

	private int 안읽은수() throws Exception {
		MockHttpServletResponse res = mockMvc.perform(get("/conversations/unread-count").cookie(구매자쿠키)).andReturn().getResponse();
		return om.readTree(res.getContentAsString()).get("count").asInt();
	}

	// ---------- 조건 1: 201통 이상은 최신 200통 + hasMore=true ----------

	@Test
	void 본인_보기_201통이면_최신_200통과_hasMore_true이고_맨_처음_쪽지는_빠진다() throws Exception {
		총_통수로_맞추기(201);
		JsonNode b = 본인_보기();
		JsonNode m = b.get("messages");
		assertThat(m.size()).isEqualTo(200);
		assertThat(b.get("hasMore").asBoolean()).isTrue();
		assertThat(m.get(0).get("content").asString()).isEqualTo("m2"); // 맨 처음 "첫째"는 빠짐
		assertThat(m.get(199).get("content").asString()).isEqualTo("m201"); // 마지막 쪽지는 들어 있음
	}

	@Test
	void 본인_보기_202통이면_앞의_2통이_빠지고_hasMore_true다() throws Exception {
		총_통수로_맞추기(202);
		JsonNode b = 본인_보기();
		assertThat(b.get("messages").size()).isEqualTo(200);
		assertThat(b.get("hasMore").asBoolean()).isTrue();
		assertThat(b.get("messages").get(0).get("content").asString()).isEqualTo("m3");
		assertThat(b.get("messages").get(199).get("content").asString()).isEqualTo("m202");
	}

	@Test
	void 관리자_보기_201통이면_최신_200통과_hasMore_true이고_맨_처음_쪽지는_빠진다() throws Exception {
		총_통수로_맞추기(201);
		JsonNode b = 관리자_보기();
		JsonNode m = b.get("messages");
		assertThat(m.size()).isEqualTo(200);
		assertThat(b.get("hasMore").asBoolean()).isTrue();
		assertThat(m.get(0).get("content").asString()).isEqualTo("m2");
		assertThat(m.get(199).get("content").asString()).isEqualTo("m201");
	}

	@Test
	void 관리자_보기_202통이면_앞의_2통이_빠지고_hasMore_true다() throws Exception {
		총_통수로_맞추기(202);
		JsonNode b = 관리자_보기();
		assertThat(b.get("messages").size()).isEqualTo(200);
		assertThat(b.get("hasMore").asBoolean()).isTrue();
		assertThat(b.get("messages").get(0).get("content").asString()).isEqualTo("m3");
	}

	// ---------- 조건 2: 200통 이하는 전부 + hasMore=false (경계 1, 199, 200) ----------

	@Test
	void 본인_보기_1통_199통_200통은_전부_보이고_hasMore_false다() throws Exception {
		JsonNode one = 본인_보기();
		assertThat(one.get("messages").size()).isEqualTo(1);
		assertThat(one.get("hasMore").asBoolean()).isFalse();

		총_통수로_맞추기(199);
		JsonNode b199 = 본인_보기();
		assertThat(b199.get("messages").size()).isEqualTo(199);
		assertThat(b199.get("hasMore").asBoolean()).isFalse();
		assertThat(b199.get("messages").get(0).get("content").asString()).isEqualTo("첫째");

		jdbc.update("insert into messages (conversation_id, sender_id, content, created_at) values (?, ?, 'm200', now())", 방id, 판매자.getId());
		JsonNode b200 = 본인_보기();
		assertThat(b200.get("messages").size()).isEqualTo(200);
		assertThat(b200.get("hasMore").asBoolean()).isFalse();
		assertThat(b200.get("messages").get(0).get("content").asString()).isEqualTo("첫째");
		assertThat(b200.get("messages").get(199).get("content").asString()).isEqualTo("m200");
	}

	@Test
	void 관리자_보기_1통_199통_200통은_전부_보이고_hasMore_false다() throws Exception {
		JsonNode one = 관리자_보기();
		assertThat(one.get("messages").size()).isEqualTo(1);
		assertThat(one.get("hasMore").asBoolean()).isFalse();

		총_통수로_맞추기(199);
		JsonNode b199 = 관리자_보기();
		assertThat(b199.get("messages").size()).isEqualTo(199);
		assertThat(b199.get("hasMore").asBoolean()).isFalse();

		jdbc.update("insert into messages (conversation_id, sender_id, content, created_at) values (?, ?, 'm200', now())", 방id, 판매자.getId());
		JsonNode b200 = 관리자_보기();
		assertThat(b200.get("messages").size()).isEqualTo(200);
		assertThat(b200.get("hasMore").asBoolean()).isFalse();
		assertThat(b200.get("messages").get(0).get("content").asString()).isEqualTo("첫째");
	}

	@Test
	void 쪽지가_없는_방도_hasMore_false다_본인과_관리자_모두() throws Exception {
		// 방에 쪽지가 한 통도 없는 경우(쪽지만 지운 상태)
		jdbc.update("delete from messages where conversation_id = ?", 방id);
		JsonNode b = 본인_보기();
		assertThat(b.get("messages").size()).isZero();
		assertThat(b.get("hasMore").asBoolean()).isFalse();
		JsonNode a = 관리자_보기();
		assertThat(a.get("messages").size()).isZero();
		assertThat(a.get("hasMore").asBoolean()).isFalse();
	}

	// ---------- 조건 3: id 오름차순 ----------

	@Test
	void 두_주소_모두_201통일_때도_id_오름차순이다() throws Exception {
		총_통수로_맞추기(201);
		JsonNode b = 본인_보기();
		오름차순_확인(b.get("messages"));
		JsonNode a = 관리자_보기();
		오름차순_확인(a.get("messages"));
		assertThat(마지막_id(b.get("messages"))).isEqualTo(마지막_id(a.get("messages")));
	}

	// ---------- 조건 5: 읽음 처리·안 읽은 수는 그대로 ----------

	@Test
	void 읽음_처리와_안읽은_수는_그대로이고_200통보다_오래된_안읽은_쪽지도_읽음_PUT으로_읽힌다() throws Exception {
		총_통수로_맞추기(250); // 구매자 입장에서 안 읽은 쪽지: 판매자가 보낸 249통(첫 쪽지는 구매자 본인 것)
		assertThat(안읽은수()).isEqualTo(249);

		// 보기만 해서는 읽음 처리가 안 된다
		JsonNode b = 본인_보기();
		assertThat(b.get("hasMore").asBoolean()).isTrue();
		assertThat(안읽은수()).isEqualTo(249);

		// 화면에 안 나온 가장 오래된 안 읽은 쪽지(m2)의 id
		long 가장오래된안읽음 = jdbc.queryForObject("select min(id) from messages where conversation_id = ? and read_at is null and sender_id = ?",
				Long.class, 방id, 판매자.getId());
		long 받은마지막 = 마지막_id(b.get("messages"));
		assertThat(가장오래된안읽음).isLessThan(b.get("messages").get(0).get("id").asLong());

		MockHttpServletResponse res = mockMvc.perform(put("/conversations/" + 방id + "/read").with(csrf())
				.contentType(MediaType.APPLICATION_JSON).content("{\"lastMessageId\":" + 받은마지막 + "}").cookie(구매자쿠키)).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(204);

		// 의도된 동작: 안 보인 오래된 쪽지도 받은 마지막 id 이하라서 읽음 처리된다
		Integer 읽힌 = jdbc.queryForObject("select count(*) from messages where id = ? and read_at is not null", Integer.class, 가장오래된안읽음);
		assertThat(읽힌).isEqualTo(1);
		assertThat(안읽은수()).isZero();
	}

	@Test
	void 관리자_보기는_읽음_처리를_하지_않는다() throws Exception {
		총_통수로_맞추기(250);
		관리자_보기();
		Integer 읽힘 = jdbc.queryForObject("select count(*) from messages where conversation_id = ? and read_at is not null", Integer.class, 방id);
		assertThat(읽힘).isZero();
		assertThat(안읽은수()).isEqualTo(249);
	}
}
