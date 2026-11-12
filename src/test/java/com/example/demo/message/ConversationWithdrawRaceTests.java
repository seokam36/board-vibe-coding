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
import tools.jackson.databind.ObjectMapper;

// S14-a 리뷰 반영: "회원 탈퇴(UserService.withdraw)와 쪽지 보내기가 동시에" 일어날 때 교착(deadlock, 서로 상대의 잠금을 기다리는 상태)이 안 나야 한다.
// 탈퇴는 회원 행을 FOR UPDATE로 잠근 채 글 삭제(CASCADE)·방의 buyer_id SET NULL 같은 일을 이어서 하고,
// 쪽지 보내기는 INSERT의 FK 검사로 글·방 행에 KEY SHARE 잠금을 잡은 채 회원 행을 기다릴 수 있어서 고리가 생길 수 있었다.
// 기대: 어느 쪽도 500이 아니고, 탈퇴가 이기면 쪽지는 (판매자 탈퇴 404 / 구매자 탈퇴 401).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationWithdrawRaceTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;

	User 판매자, 구매자;
	Cookie 판매자쿠키, 구매자쿠키;
	long 글id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "wseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "wbuyer", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		String body = "{\"title\":\"자전거\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 중고 글 올리기").isEqualTo(201);
		글id = om.readTree(r.getContentAsString()).get("id").asLong();
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private int 보내기(String content) {
		try {
			return mockMvc.perform(post("/market-items/" + 글id + "/conversations").with(csrf())
					.contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"" + content + "\"}").cookie(구매자쿠키))
					.andReturn().getResponse().getStatus();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// UserService.withdraw가 하는 일과 같은 순서의 문장들(회원 행 잠금은 이미 잡은 상태에서 이어서 실행한다):
	// 글·댓글·후기 작성자 비우기 -> 이 회원 글 행 잠금(사진 이름 기록용 enqueueSellerPhotos) -> 회원 삭제(CASCADE·SET NULL 발동)
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

	// 경우 A: 판매자 탈퇴(잠금 보유) 중에 새 쪽지가 들어온다. 쪽지 INSERT가 글 행 KEY SHARE를 잡고 판매자 행에서 기다리면,
	// 탈퇴의 글 삭제(CASCADE)가 글 행에서 기다려 서로 기다리는 고리(교착)가 된다.
	@Test
	void 경우A_판매자_탈퇴_중에_새_쪽지를_보내면_404이고_500이_아니다() throws Exception {
		long sid = 판매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> 회원_행_잠그기(sid),
				() -> 보내기("안녕하세요"),
				() -> 탈퇴_나머지(sid));

		assertThat(status).as("판매자 탈퇴가 이기면 글도 사라졌으니 404").isEqualTo(404);
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from conversations", Integer.class)).isZero();
	}

	// 경우 B: 구매자가 기존 방에 쪽지를 보내는 중에 같은 구매자가 탈퇴한다. 쪽지 INSERT가 방 행 KEY SHARE를 잡고
	// sender(구매자 행)에서 기다리면, 탈퇴의 방 buyer_id SET NULL(UNIQUE 칸이라 강한 잠금)이 방 행에서 기다려 교착이 된다.
	@Test
	void 경우B_구매자_탈퇴_중에_기존_방에_쪽지를_보내면_401이고_500이_아니다() throws Exception {
		assertThat(보내기("첫째")).isEqualTo(201); // 방과 쪽지 1개를 먼저 만든다
		long bid = 구매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> 회원_행_잠그기(bid),
				() -> 보내기("둘째"),
				() -> 탈퇴_나머지(bid));

		assertThat(status).as("구매자 탈퇴가 이기면 로그인 풀림 401").isEqualTo(401);
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).as("새 쪽지 없음").isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from conversations", Integer.class)).isEqualTo(1);
	}

	// 경우 C(반대 순서): 쪽지가 먼저 회원 행 KEY SHARE를 잡고 진행 중일 때 구매자의 탈퇴가 시작된다.
	// 탈퇴(FOR UPDATE)는 쪽지가 끝나기를 기다렸다가 정상 완료돼야 하고(204), 쪽지는 201이어야 한다. 500·교착이 없어야 한다.
	// 재현: 다른 트랜잭션이 같은 (글, 구매자) 방을 커밋 전 INSERT해 둔다 -> 진짜 쪽지 요청이 회원 행 잠금을 잡은 뒤 방 INSERT에서 기다린다
	//       -> 그 뒤에 탈퇴 요청을 보낸다(회원 행 FOR UPDATE에서 기다림) -> 방 INSERT 트랜잭션 커밋 -> 쪽지 201 -> 탈퇴 204.
	@Test
	void 경우C_쪽지가_회원_행을_먼저_잡은_뒤_탈퇴가_시작되면_쪽지_201_탈퇴_204이고_구매자_칸은_NULL이다() throws Exception {
		long bid = 구매자.getId();
		long sid = 판매자.getId();
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			java.util.concurrent.Future<Integer> 쪽지;
			java.util.concurrent.Future<Integer> 탈퇴;
			var holder = new java.util.concurrent.Future[2];
			new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(status -> {
				// 방을 커밋 전에 만들어 둔다(UNIQUE 충돌 대기를 만드는 장치)
				jdbc.update("insert into conversations (market_item_id, item_title, buyer_id, seller_id, created_at, last_message_at) "
						+ "values (?, '자전거', ?, ?, now(), now())", 글id, bid, sid);
				holder[0] = pool.submit(() -> 보내기("안녕하세요"));
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 1); // 쪽지 요청이 방 INSERT에서 기다리는 중 = 회원 행 KEY SHARE는 이미 잡았다
					holder[1] = pool.submit(() -> mockMvc.perform(
							org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/users/me").with(csrf())
									.cookie(구매자쿠키).contentType(MediaType.APPLICATION_JSON)
									.content("{\"password\":\"unused-password\"}"))
							.andReturn().getResponse().getStatus());
					LockRaceSupport.락대기_기다리기(jdbc, 2); // 탈퇴도 기다리는 중
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertThat(holder[0].isDone()).isFalse();
				assertThat(holder[1].isDone()).isFalse();
			}); // 커밋 -> 쪽지가 깨어나 끝나고, 그 뒤 탈퇴가 진행된다
			쪽지 = holder[0];
			탈퇴 = holder[1];
			assertThat(쪽지.get(10, java.util.concurrent.TimeUnit.SECONDS)).as("쪽지").isEqualTo(201);
			assertThat(탈퇴.get(10, java.util.concurrent.TimeUnit.SECONDS)).as("탈퇴").isEqualTo(204);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
		}
		assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, bid)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from conversations where buyer_id is null and seller_id = ?", Integer.class, sid))
				.as("방은 남고 구매자 칸만 NULL").isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from messages where sender_id is null", Integer.class))
				.as("쪽지는 남고 보낸 사람만 NULL").isEqualTo(1);
	}
}
