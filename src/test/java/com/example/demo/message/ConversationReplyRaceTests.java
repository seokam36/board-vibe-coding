package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import tools.jackson.databind.ObjectMapper;

// S14-b: "답장 / 기존 방에 쪽지 보내기"와 "회원 탈퇴(UserService.withdraw)"가 동시에 일어날 때 교착(500)이 없고,
// 탈퇴한 상대에게 쪽지가 들어가는 틈이 없는지 확인한다. 설명은 ConversationService.reply의 잠금 순서 주석 참고.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationReplyRaceTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;

	User 판매자, 구매자;
	Cookie 판매자쿠키, 구매자쿠키;
	long 글id, 방id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "pseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "pbuyer", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		String body = "{\"title\":\"자전거\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 중고 글 올리기").isEqualTo(201);
		글id = om.readTree(r.getContentAsString()).get("id").asLong();
		// 방과 첫 쪽지를 미리 만들어 둔다(이 클래스의 경쟁은 모두 "이미 있는 방"에서 일어난다)
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

	private int 답장(Cookie cookie, String content) {
		try {
			return mockMvc.perform(post("/conversations/" + 방id + "/messages").with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}").cookie(cookie))
					.andReturn().getResponse().getStatus();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private int 기존_방에_첫_쪽지_API로_보내기(Cookie cookie, String content) {
		try {
			return mockMvc.perform(post("/market-items/" + 글id + "/conversations").with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}").cookie(cookie))
					.andReturn().getResponse().getStatus();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// UserService.withdraw가 하는 일과 같은 순서의 문장들(회원 행 잠금은 이미 잡은 상태에서 이어서 실행한다)
	private void 탈퇴_나머지(long userId) {
		jdbc.update("update posts set author_id = null where author_id = ?", userId);
		jdbc.update("update comments set author_id = null where author_id = ?", userId);
		jdbc.update("update visa_reviews set author_id = null where author_id = ?", userId);
		jdbc.queryForList("select id from market_items where seller_id = ? order by id for update", Long.class, userId);
		jdbc.update("delete from users where id = ?", userId);
	}

	private void 회원_행_잠그기(long userId) {
		jdbc.queryForList("select id from users where id = ? for update", Long.class, userId);
	}

	private int 메시지_수() {
		return jdbc.queryForObject("select count(*) from messages", Integer.class);
	}

	// 경우 A: 상대(판매자)가 탈퇴하는 중(회원 행 FOR UPDATE 보유)에 구매자가 답장한다.
	// 답장은 쪽지 INSERT 전에 상대 회원 행 KEY SHARE를 잡으려다 여기서 기다리고, 탈퇴가 커밋되면 행이 없어 409.
	// (잠금을 안 잡는 코드라면 기다리지 않고 바로 201이 나가 "탈퇴하는 상대에게 쪽지가 들어가는 틈"이 생긴다)
	@Test
	void 경우A_상대_탈퇴_중에_답장하면_409이고_500이_아니다() throws Exception {
		long sid = 판매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> 회원_행_잠그기(sid),
				() -> 답장(구매자쿠키, "둘째"),
				() -> 탈퇴_나머지(sid));

		assertThat(status).as("상대 탈퇴가 이기면 409").isEqualTo(409);
		assertThat(메시지_수()).as("새 쪽지 없음").isEqualTo(1);
	}

	// 경우 A': 반대 방향. 구매자가 탈퇴하는 중에 판매자가 답장 -> 409.
	@Test
	void 경우A2_구매자_탈퇴_중에_판매자가_답장하면_409이고_500이_아니다() throws Exception {
		long bid = 구매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> 회원_행_잠그기(bid),
				() -> 답장(판매자쿠키, "둘째"),
				() -> 탈퇴_나머지(bid));

		assertThat(status).isEqualTo(409);
		assertThat(메시지_수()).isEqualTo(1);
	}

	// 경우 B: 내가 탈퇴하는 중에 내 토큰으로 답장 -> 로그인 풀림 401 (500 아님)
	@Test
	void 경우B_내가_탈퇴하는_중에_답장하면_401이고_500이_아니다() throws Exception {
		long bid = 구매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> 회원_행_잠그기(bid),
				() -> 답장(구매자쿠키, "둘째"),
				() -> 탈퇴_나머지(bid));

		assertThat(status).isEqualTo(401);
		assertThat(메시지_수()).isEqualTo(1);
	}

	// 경우 D: S14-a 리뷰 "기존 방에 붙이는 중 판매자 탈퇴가 커밋되면 seller_id NULL 방에 201" 이 닫혀 있는지.
	// 판매자가 탈퇴하는 중(회원 행 보유)에 구매자가 "첫 쪽지 API"로 기존 방에 붙이면, 판매자 행 KEY SHARE를 기다렸다가 행이 없어 404.
	@Test
	void 경우D_판매자_탈퇴_중에_기존_방에_첫_쪽지_API로_붙이면_404이고_쪽지는_늘지_않는다() throws Exception {
		long sid = 판매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> 회원_행_잠그기(sid),
				() -> 기존_방에_첫_쪽지_API로_보내기(구매자쿠키, "둘째"),
				() -> 탈퇴_나머지(sid));

		assertThat(status).as("판매자 탈퇴가 이기면 404").isEqualTo(404);
		assertThat(메시지_수()).isEqualTo(1);
	}

	// 경우 C(반대 순서): 답장이 회원 행 KEY SHARE를 먼저 잡고 진행 중일 때 상대(판매자)의 탈퇴가 시작된다.
	// 탈퇴는 첫 잠금(회원 행 FOR UPDATE)에서 답장이 끝나기를 기다렸다 정상 완료(204)해야 하고, 답장은 201이어야 한다. 교착·500 없음.
	// 재현: 다른 트랜잭션이 방 행을 FOR UPDATE로 잡아 둔다 -> 답장이 회원 행 잠금을 잡은 뒤 쪽지 INSERT(FK 검사)에서 기다린다
	//       -> 탈퇴 요청을 보낸다(회원 행에서 기다림) -> 방 행 잠금 해제 -> 답장 201 -> 탈퇴 204.
	@Test
	void 경우C_답장이_회원_행을_먼저_잡은_뒤_상대_탈퇴가_시작되면_답장_201_탈퇴_204이다() throws Exception {
		long sid = 판매자.getId();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			@SuppressWarnings("unchecked")
			Future<Integer>[] holder = new Future[2];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				jdbc.queryForList("select id from conversations where id = ? for update", Long.class, 방id);
				holder[0] = pool.submit(() -> 답장(구매자쿠키, "둘째"));
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 1); // 답장이 방 행에서 기다리는 중 = 회원 행 KEY SHARE는 이미 잡았다
					holder[1] = pool.submit(() -> mockMvc.perform(delete("/users/me").with(csrf()).cookie(판매자쿠키)
							.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"unused-password\"}"))
							.andReturn().getResponse().getStatus());
					LockRaceSupport.락대기_기다리기(jdbc, 2); // 탈퇴도 기다리는 중
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertThat(holder[0].isDone()).isFalse();
				assertThat(holder[1].isDone()).isFalse();
			}); // 커밋(방 행 잠금 해제) -> 답장이 끝나고, 그 뒤 탈퇴가 진행된다
			assertThat(holder[0].get(10, TimeUnit.SECONDS)).as("답장").isEqualTo(201);
			assertThat(holder[1].get(10, TimeUnit.SECONDS)).as("탈퇴").isEqualTo(204);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, sid)).isZero();
		assertThat(메시지_수()).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from conversations where seller_id is null and buyer_id = ?",
				Integer.class, 구매자.getId())).as("방은 남고 판매자 칸만 NULL").isEqualTo(1);
	}
}
