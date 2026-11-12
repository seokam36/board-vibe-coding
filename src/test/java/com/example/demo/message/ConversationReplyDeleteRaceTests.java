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

// S14-b 리뷰 권장 1·3: 같은 방에 "답장 A, 답장 B, 그 글 삭제 D"가 겹칠 때 교착(40P01 -> 500)이 없어야 하고,
// 같은 방에 답장 둘이 동시에 와도 둘 다 201이며 쪽지 시각·마지막 쪽지 시각이 어긋나지 않아야 한다.
// 원리(ConversationService.appendMessage 주석 참고): 쪽지 INSERT의 FK 검사가 방 행에 약한 잠금을 잡은 채 UPDATE로 강한 잠금을 원하면,
// 글 삭제(방의 market_item_id SET NULL = 가장 센 잠금)가 줄 사이에 끼어 "삭제 <-> 답장" 고리가 생긴다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationReplyDeleteRaceTests {

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
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "dseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "dbuyer", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		String body = "{\"title\":\"자전거\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(body).cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 중고 글 올리기").isEqualTo(201);
		글id = om.readTree(r.getContentAsString()).get("id").asLong();
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

	private int 글_삭제() {
		try {
			return mockMvc.perform(delete("/market-items/" + 글id).with(csrf()).cookie(판매자쿠키))
					.andReturn().getResponse().getStatus();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	// "답장 A가 방 행을 쥔 채 진행 중" + "글 삭제 D" + "답장 B"가 겹치는 상황을 결정적으로 만든다.
	// 테스트 트랜잭션이 A 역할로 방 행을 FOR NO KEY UPDATE로 잡고(커밋 안 함) -> D(방의 market_item_id SET NULL, 가장 센 잠금)가 줄 맨 앞에서 기다린다
	// -> 진짜 답장 B가 온다 -> A 커밋. 기대: 글 삭제 204, 답장 201, 교착(500) 없음.
	// [재현 결과] 리뷰 추정("B가 D 뒤에 줄을 서서 D<->B 고리")은 고치기 전 코드에서도 일어나지 않았다(이 테스트가 고치기 전에도 통과).
	// 이유: PostgreSQL heap_update는 "이미 그 행에 자기 잠금(쪽지 INSERT의 FK 검사 KEY SHARE)을 가진 트랜잭션"은
	// 다른 대기자의 줄(heavyweight tuple lock)에 서지 않고 바로 상대 트랜잭션 종료만 기다리게 해 이 고리를 일부러 피한다(pg_locks로 확인).
	// 그래서 이 테스트는 "고리 재현"이 아니라 "고친 뒤에도 B가 방 행 잠금에서 D 뒤에 서서 정상 완료한다"는 회귀 확인이다.
	@Test
	void 답장과_글_삭제가_겹쳐도_교착_500이_없다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			@SuppressWarnings("unchecked")
			Future<Integer>[] holder = new Future[2];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				jdbc.queryForList("select id from conversations where id = ? for no key update", Long.class, 방id);
				holder[0] = pool.submit(this::글_삭제);
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 1); // D가 방 행에서 A를 기다리는 중
					holder[1] = pool.submit(() -> 답장(판매자쿠키, "B"));
					LockRaceSupport.락대기_기다리기(jdbc, 2); // B도 기다리는 중
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertThat(holder[0].isDone()).isFalse();
				assertThat(holder[1].isDone()).isFalse();
			}); // 커밋
			assertThat(holder[0].get(10, TimeUnit.SECONDS)).as("글 삭제").isEqualTo(204);
			assertThat(holder[1].get(10, TimeUnit.SECONDS)).as("답장 B").isEqualTo(201);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).as("첫째 + B").isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from conversations where market_item_id is null", Integer.class))
				.as("글이 지워져 방의 글 칸은 NULL").isEqualTo(1);
	}

	// 권장 3: 같은 방에 답장 둘이 동시에. 방 행을 테스트가 잡아 둔 채 둘을 줄 세웠다가 풀어 준다.
	// 기대: 둘 다 201, 쪽지 +2, 방의 마지막 쪽지 시각 = 쪽지 시각 중 가장 큰 값, 쪽지 id 순서와 시각 순서가 어긋나지 않는다.
	@Test
	void 같은_방에_답장_둘이_동시에_와도_둘_다_201이고_시각이_어긋나지_않는다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			@SuppressWarnings("unchecked")
			Future<Integer>[] holder = new Future[2];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				jdbc.queryForList("select id from conversations where id = ? for update", Long.class, 방id);
				// 한 명씩 차례로 줄을 세운다(도착 순서 = 둘째, 셋째). 고친 뒤에는 방 행 잠금이 줄 순서대로 풀려서 id·시각 순서가 결정적이다.
				holder[0] = pool.submit(() -> 답장(구매자쿠키, "둘째"));
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 1);
					holder[1] = pool.submit(() -> 답장(판매자쿠키, "셋째"));
					LockRaceSupport.락대기_기다리기(jdbc, 2); // 두 답장 모두 줄을 섰다
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
			});
			assertThat(holder[0].get(10, TimeUnit.SECONDS)).isEqualTo(201);
			assertThat(holder[1].get(10, TimeUnit.SECONDS)).isEqualTo(201);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertThat(jdbc.queryForObject("select count(*) from messages", Integer.class)).as("첫째 + 둘 = 3").isEqualTo(3);
		assertThat(jdbc.queryForObject("select c.last_message_at = (select max(m.created_at) from messages m where m.conversation_id = c.id) "
				+ "from conversations c where c.id = ?", Boolean.class, 방id)).as("마지막 쪽지 시각 = 쪽지 시각 중 최대").isTrue();
		assertThat(jdbc.queryForObject("select count(*) from messages m1 join messages m2 on m1.id < m2.id and m1.created_at > m2.created_at",
				Integer.class)).as("id가 작은데 시각이 더 늦은 쪽지가 없다").isZero();
	}
}
