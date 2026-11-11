package com.example.demo.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

// S13-c 완료 조건 검사: 물건 글 상태 바꾸기(PUT /market-items/{id}/status)와 그 경쟁 상황,
// 거래완료 글의 수정 막음(409).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemStatusTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;

	User 회원; // 연세대 인증 판매자
	User 남; // 고려대 인증 다른 회원
	User 관리자;
	Cookie 쿠키;
	Cookie 남쿠키;
	Cookie 관리자쿠키;

	@BeforeEach
	void 준비() {
		정리();
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "suser", "연세대학교");
		남 = TestLogin.인증된_사용자(userRepository, jdbc, "sother", "고려대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "sadmin");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		남쿠키 = TestLogin.쿠키(tokenService, 남);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private long 올리기(Cookie c) throws Exception {
		String json = "{\"title\":\"옛제목\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(json).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	// 글을 올리고 DB에서 바로 원하는 상태로 맞춘다(전환 규칙과 무관하게 출발 상태를 만들려고)
	private long 올리기(Cookie c, String 상태) throws Exception {
		long id = 올리기(c);
		jdbc.update("update market_items set status = ? where id = ?", 상태, id);
		return id;
	}

	private static String 상태본문(String status) {
		return "{\"status\":\"" + status + "\"}";
	}

	private MockHttpServletResponse 상태변경(long id, String json, Cookie c, boolean withCsrf) throws Exception {
		MockHttpServletRequestBuilder b = put("/market-items/" + id + "/status").contentType(MediaType.APPLICATION_JSON)
				.content(json);
		if (c != null) {
			b.cookie(c);
		}
		if (withCsrf) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private int 상태변경코드(long id, String status, Cookie c) throws Exception {
		return 상태변경(id, 상태본문(status), c, true).getStatus();
	}

	private MockHttpServletResponse 내용수정(long id, Cookie c) throws Exception {
		String json = "{\"title\":\"새제목\",\"content\":\"새내용\",\"tradeType\":\"SALE\",\"price\":7000}";
		return mockMvc.perform(put("/market-items/" + id).contentType(MediaType.APPLICATION_JSON).content(json)
				.cookie(c).with(csrf())).andReturn().getResponse();
	}

	private String DB상태(long id) {
		return jdbc.queryForObject("select status from market_items where id = ?", String.class, id);
	}

	private String 제목(long id) {
		return jdbc.queryForObject("select title from market_items where id = ?", String.class, id);
	}

	private int 가격(long id) {
		return jdbc.queryForObject("select price from market_items where id = ?", Integer.class, id);
	}

	private long 글수(long id) {
		return jdbc.queryForObject("select count(*) from market_items where id = ?", Long.class, id);
	}

	// ============ 허용되는 전환 ============

	private void 허용전환_확인(String from, String to) throws Exception {
		long id = 올리기(쿠키, from);
		MockHttpServletResponse r = 상태변경(id, 상태본문(to), 쿠키, true);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode b = om.readTree(r.getContentAsString());
		assertThat(b.get("id").asLong()).isEqualTo(id);
		assertThat(b.get("status").get("code").asString()).isEqualTo(to);
		assertThat(DB상태(id)).isEqualTo(to);
	}

	@Test
	void 판매중에서_예약중으로_바꾸면_200이고_상태가_바뀐다() throws Exception {
		// 무엇/왜: 허용 전환 ON_SALE->RESERVED가 응답과 DB에 모두 반영되는지
		허용전환_확인("ON_SALE", "RESERVED");
	}

	@Test
	void 판매중에서_거래완료로_바꾸면_200이고_상태가_바뀐다() throws Exception {
		허용전환_확인("ON_SALE", "DONE");
	}

	@Test
	void 예약중에서_판매중으로_되돌리면_200이고_상태가_바뀐다() throws Exception {
		허용전환_확인("RESERVED", "ON_SALE");
	}

	@Test
	void 예약중에서_거래완료로_바꾸면_200이고_상태가_바뀐다() throws Exception {
		허용전환_확인("RESERVED", "DONE");
	}

	// ============ 막히는 전환 ============

	private void 막힌전환_확인(String from, String to) throws Exception {
		long id = 올리기(쿠키, from);
		assertThat(상태변경코드(id, to, 쿠키)).isEqualTo(409);
		assertThat(DB상태(id)).as("409면 DB 상태는 그대로").isEqualTo(from);
	}

	@Test
	void 거래완료에서_판매중으로는_409이고_상태는_그대로다() throws Exception {
		// 무엇/왜: DONE은 끝난 거래라 어디로도 못 나간다
		막힌전환_확인("DONE", "ON_SALE");
	}

	@Test
	void 거래완료에서_예약중으로는_409이고_상태는_그대로다() throws Exception {
		막힌전환_확인("DONE", "RESERVED");
	}

	@Test
	void 판매중에서_판매중_같은_상태로는_409다() throws Exception {
		막힌전환_확인("ON_SALE", "ON_SALE");
	}

	@Test
	void 예약중에서_예약중_같은_상태로는_409다() throws Exception {
		막힌전환_확인("RESERVED", "RESERVED");
	}

	@Test
	void 거래완료에서_거래완료_같은_상태로는_409다() throws Exception {
		막힌전환_확인("DONE", "DONE");
	}

	// ============ 권한·입력 거절 ============

	@Test
	void 남의_글_상태_변경은_403이고_상태는_그대로다() throws Exception {
		long id = 올리기(쿠키);
		assertThat(상태변경코드(id, "DONE", 남쿠키)).isEqualTo(403);
		assertThat(DB상태(id)).isEqualTo("ON_SALE");
	}

	@Test
	void 관리자도_남의_글_상태_변경은_403이다() throws Exception {
		// 왜: 삭제와 달리 상태 변경은 관리자 예외가 없다
		long id = 올리기(쿠키);
		assertThat(상태변경코드(id, "DONE", 관리자쿠키)).isEqualTo(403);
		assertThat(DB상태(id)).isEqualTo("ON_SALE");
	}

	@Test
	void 없는_id의_상태_변경은_404다() throws Exception {
		assertThat(상태변경코드(999999999L, "DONE", 쿠키)).isEqualTo(404);
	}

	@Test
	void 없는_상태_값_SOLD는_400이고_상태는_그대로다() throws Exception {
		long id = 올리기(쿠키);
		assertThat(상태변경(id, 상태본문("SOLD"), 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(DB상태(id)).isEqualTo("ON_SALE");
	}

	@Test
	void status가_빠진_JSON이면_400이다() throws Exception {
		// 문법은 맞는 JSON({}, status:null, 다른 키만)이라 @NotNull 검증이 400을 내야 한다
		long id = 올리기(쿠키);
		assertThat(상태변경(id, "{}", 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(상태변경(id, "{\"status\":null}", 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(상태변경(id, "{\"other\":\"DONE\"}", 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(DB상태(id)).isEqualTo("ON_SALE");
	}

	@Test
	void 진짜_빈_본문과_깨진_JSON을_보내면_400이고_상태는_그대로다() throws Exception {
		// 무엇: 본문 ""(아예 없음)와 "{"(깨진 JSON) / 왜: 읽을 수 없는 본문은 500이 아니라 400이어야 해서
		long id = 올리기(쿠키);
		assertThat(상태변경(id, "", 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(상태변경(id, "{", 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(DB상태(id)).isEqualTo("ON_SALE");
	}

	@Test
	void 숫자_상태_값이면_400이고_상태는_그대로다() throws Exception {
		// 무엇: {"status":1} / 왜: 숫자(enum 순서)로 상태가 정해지면 안 되고 400으로 거절해야 해서
		long id = 올리기(쿠키);
		assertThat(상태변경(id, "{\"status\":1}", 쿠키, true).getStatus()).isEqualTo(400);
		assertThat(DB상태(id)).isEqualTo("ON_SALE");
	}

	@Test
	void 상태를_바꿔도_제목_본문_가격_종류는_그대로다() throws Exception {
		// 무엇: 상태 변경 후 DB의 나머지 칸 / 왜: 상태 변경이 다른 내용을 건드리면 안 돼서
		long id = 올리기(쿠키);
		assertThat(상태변경코드(id, "RESERVED", 쿠키)).isEqualTo(200);
		var row = jdbc.queryForMap("select title, content, price, trade_type, status from market_items where id = ?", id);
		assertThat(row.get("title")).isEqualTo("옛제목");
		assertThat(row.get("content")).isEqualTo("내용");
		assertThat(((Number) row.get("price")).intValue()).isEqualTo(1000);
		assertThat(row.get("trade_type")).isEqualTo("SALE");
		assertThat(row.get("status")).isEqualTo("RESERVED");
	}

	@Test
	void 탈퇴한_회원_토큰으로_남의_글_상태_변경은_403이고_상태는_그대로다() throws Exception {
		// 무엇: 유령 일반 회원의 상태 변경 / 왜: 글 주인이 아니므로 403이어야 해서
		long id = 올리기(쿠키);
		jdbc.update("delete from users where id = ?", 남.getId());
		assertThat(상태변경코드(id, "DONE", 남쿠키)).isEqualTo(403);
		assertThat(DB상태(id)).isEqualTo("ON_SALE");
	}

	@Test
	void 비로그인은_401이고_CSRF가_없으면_403이다() throws Exception {
		long id = 올리기(쿠키);
		assertThat(상태변경(id, 상태본문("DONE"), null, true).getStatus()).isEqualTo(401);
		assertThat(상태변경(id, 상태본문("DONE"), 쿠키, false).getStatus()).isEqualTo(403);
		assertThat(DB상태(id)).isEqualTo("ON_SALE");
	}

	@Test
	void 학교_인증을_해제한_판매자도_자기_글_상태_변경은_200이다() throws Exception {
		// 왜: 상태 변경은 학교 인증을 요구하지 않는다
		long id = 올리기(쿠키);
		jdbc.update("update users set verified_school_id = null, verified_at = null, school_email_hash = null where id = ?",
				회원.getId());
		assertThat(상태변경코드(id, "DONE", 쿠키)).isEqualTo(200);
		assertThat(DB상태(id)).isEqualTo("DONE");
	}

	// ============ 검사 순서 ============

	@Test
	void 순서_남의_글에_잘못된_상태_값이면_403이_아니라_400이다() throws Exception {
		// 왜: 본문 검사(400)가 소유 확인(403)보다 먼저
		long id = 올리기(쿠키);
		assertThat(상태변경코드(id, "SOLD", 남쿠키)).isEqualTo(400);
	}

	@Test
	void 순서_없는_id에_잘못된_상태_값이면_404가_아니라_400이다() throws Exception {
		assertThat(상태변경코드(999999999L, "SOLD", 쿠키)).isEqualTo(400);
	}

	@Test
	void 순서_남의_DONE_글에_불가_전환이면_409가_아니라_403이다() throws Exception {
		// 왜: 소유 확인(403)이 전환 규칙(409)보다 먼저라서 남에게 글 상태가 새지 않는다
		long id = 올리기(쿠키, "DONE");
		assertThat(상태변경코드(id, "ON_SALE", 남쿠키)).isEqualTo(403);
		assertThat(DB상태(id)).isEqualTo("DONE");
	}

	// ============ 거래완료 글의 수정·삭제 ============

	@Test
	void 거래완료_글의_내용_수정은_409이고_제목_가격은_그대로다() throws Exception {
		long id = 올리기(쿠키, "DONE");
		assertThat(내용수정(id, 쿠키).getStatus()).isEqualTo(409);
		assertThat(제목(id)).isEqualTo("옛제목");
		assertThat(가격(id)).isEqualTo(1000);
		assertThat(DB상태(id)).isEqualTo("DONE");
	}

	@Test
	void 거래완료_글도_삭제는_204다() throws Exception {
		long id = 올리기(쿠키, "DONE");
		int code = mockMvc.perform(delete("/market-items/" + id).cookie(쿠키).with(csrf())).andReturn().getResponse()
				.getStatus();
		assertThat(code).isEqualTo(204);
		assertThat(글수(id)).isZero();
	}

	// ============ 경쟁 ============

	@Test
	void 경쟁1_상태_변경_두_요청이_겹치면_하나만_200_하나는_409다() throws Exception {
		// 무엇: ON_SALE 글에 DONE 요청과 RESERVED 요청을 같은 순간에 보냄 / 왜: 조건부 UPDATE 덕에 하나만 이기고 나머지는 409여야 해서
		// 결정적 재현: (1) 행을 FOR UPDATE로 잠금 (2) 두 요청 모두 findById(ON_SALE)를 읽고 UPDATE에서 잠금 대기
		// (3) 대기자가 2명이 된 것을 확인 (4) 잠금 해제 -> 먼저 깬 쪽이 1행, 늦게 깬 쪽은 status=ON_SALE 조건이 깨져 0행 -> 409
		long id = 올리기(쿠키);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> toDone;
			Future<Integer> toReserved;
			TransactionTemplate tx = new TransactionTemplate(txManager);
			Future<?>[] holder = new Future<?>[2];
			tx.executeWithoutResult(s -> {
				jdbc.queryForList("select id from market_items where id = ? for update", id);
				Callable<Integer> a = () -> 상태변경코드(id, "DONE", 쿠키);
				Callable<Integer> b = () -> 상태변경코드(id, "RESERVED", 쿠키);
				holder[0] = pool.submit(a);
				holder[1] = pool.submit(b);
				try {
					두명_락대기_기다리기();
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertThat(holder[0].isDone()).as("요청 A는 락 대기 중").isFalse();
				assertThat(holder[1].isDone()).as("요청 B는 락 대기 중").isFalse();
			}); // 커밋 = 잠금 해제
			@SuppressWarnings("unchecked")
			Future<Integer> f0 = (Future<Integer>) holder[0];
			@SuppressWarnings("unchecked")
			Future<Integer> f1 = (Future<Integer>) holder[1];
			toDone = f0;
			toReserved = f1;
			int a = toDone.get(10, TimeUnit.SECONDS);
			int b = toReserved.get(10, TimeUnit.SECONDS);
			assertThat(List.of(a, b)).containsExactlyInAnyOrder(200, 409);
			// DB 상태는 이긴 쪽의 값이어야 한다
			String expected = a == 200 ? "DONE" : "RESERVED";
			assertThat(DB상태(id)).isEqualTo(expected);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	// 락 때문에 기다리는 커넥션이 2개가 될 때까지 최대 5초 폴링
	private void 두명_락대기_기다리기() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5000;
		while (System.currentTimeMillis() < deadline) {
			Long n = jdbc.queryForObject("select count(*) from pg_stat_activity where datname = current_database() "
					+ "and wait_event_type = 'Lock' and pid <> pg_backend_pid()", Long.class);
			if (n != null && n >= 2) {
				return;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("5초 안에 락 대기자 2명이 생기지 않았다");
	}

	@Test
	void 경쟁2_내용_수정이_읽은_뒤_다른_쪽이_DONE을_커밋하면_PUT은_409이고_내용은_그대로이며_DONE이_남는다() throws Exception {
		// 무엇: PUT이 글을 읽은 뒤 UPDATE 전에 다른 쪽이 status=DONE을 커밋 / 왜: 내용 수정이 상태를 옛 값(ON_SALE)으로 되돌리면 안 돼서
		// 재현: 행 잠금 -> PUT이 findById(ON_SALE)를 읽고 조건부 UPDATE에서 대기 -> 잠근 쪽이 DONE으로 바꾸고 커밋
		//  -> 깨어난 UPDATE는 "status<>DONE" 조건이 깨져 0행 -> 존재하니 409
		long id = 올리기(쿠키);
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from market_items where id = ? for update", id),
				() -> 내용수정(id, 쿠키).getStatus(),
				() -> jdbc.update("update market_items set status = 'DONE' where id = ?", id));
		assertThat(status).isEqualTo(409);
		assertThat(제목(id)).as("내용은 그대로").isEqualTo("옛제목");
		assertThat(가격(id)).isEqualTo(1000);
		assertThat(DB상태(id)).as("상태가 옛 값으로 돌아가지 않고 DONE으로 남아야 한다").isEqualTo("DONE");
	}

	@Test
	void 경쟁3_상태_변경_중에_글이_지워지면_404다() throws Exception {
		// 무엇: 상태 변경이 글을 읽은 뒤 UPDATE 전에 글이 삭제 커밋 / 왜: 0행이어도 500이 아니라 404여야 해서
		long id = 올리기(쿠키);
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from market_items where id = ? for update", id),
				() -> 상태변경코드(id, "DONE", 쿠키),
				() -> jdbc.update("delete from market_items where id = ?", id));
		assertThat(status).isEqualTo(404);
		assertThat(글수(id)).isZero();
	}
}
