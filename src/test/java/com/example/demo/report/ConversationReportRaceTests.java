package com.example.demo.report;

import static com.example.demo.report.ConversationReportFixture.본문;
import static com.example.demo.report.ConversationReportFixture.신고;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import java.util.concurrent.CountDownLatch;
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

// S14-f: "대화 신고"와 "회원 탈퇴(UserService.withdraw)"가 동시에 일어날 때 교착(500)이 없는지 확인한다.
// 설명은 ReportService.reportConversation의 잠금 순서 주석 참고. 경쟁은 LockRaceSupport로 결정적으로 만든다(운에 맡기지 않는다).
// 주의: 이 테스트들이 확인하는 것은 "교착·500이 없다"는 결과뿐이다. reportConversation의 lockForKeyShare 줄 자체를 지켜 주지는 못한다
// (그 줄을 빼도 테스트 DB에서는 FK 검사가 신고자 회원 행부터라 결과가 같다). 그 줄은 FK 검사 순서가 다른 환경을 대비한 보험이다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class ConversationReportRaceTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;

	User 판매자, 구매자;
	Cookie 판매자쿠키, 구매자쿠키;
	long 방id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "rrseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "rrbuyer", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		방id = ConversationReportFixture.글과_방_만들기(mockMvc, om, 판매자쿠키, 구매자쿠키, "자전거")[1];
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private int 신고행수() {
		return jdbc.queryForObject("select count(*) from reports", Integer.class);
	}

	// (이름을 신고()로 하면 static import한 신고(...)를 가려 버려서 다르게 지었다)
	private int 신고하기(Cookie cookie) {
		try {
			return 신고(mockMvc, cookie, 방id, 본문("SPAM")).getStatus();		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private int 탈퇴(Cookie cookie) {
		try {
			return mockMvc.perform(delete("/users/me").with(csrf()).cookie(cookie).contentType(MediaType.APPLICATION_JSON)
					.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private void 회원_행_잠그기(long userId) {
		jdbc.queryForList("select id from users where id = ? for update", Long.class, userId);
	}

	// UserService.withdraw가 하는 일과 같은 순서의 문장들(회원 행 잠금은 이미 잡은 상태에서 이어서 실행한다)
	private void 탈퇴_나머지(long userId) {
		jdbc.update("update posts set author_id = null where author_id = ?", userId);
		jdbc.update("update comments set author_id = null where author_id = ?", userId);
		jdbc.update("update visa_reviews set author_id = null where author_id = ?", userId);
		jdbc.queryForList("select id from market_items where seller_id = ? order by id for update", Long.class, userId);
		jdbc.update("delete from users where id = ?", userId);
	}

	// 경우 A: 신고자가 탈퇴하는 중(회원 행 FOR UPDATE 보유)에 신고 -> 401이고 신고는 저장되지 않는다(500 아님).
	// (결과만 확인한다. lockForKeyShare 줄이 없어도 FK 검사가 신고자부터라 같은 결과가 나오므로 그 줄을 직접 지키지는 못한다)
	@Test
	void 경우A_신고자가_탈퇴하는_중에_신고하면_401이고_500이_아니다() throws Exception {
		long bid = 구매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> 회원_행_잠그기(bid),
				() -> 신고하기(구매자쿠키),
				() -> 탈퇴_나머지(bid));

		assertThat(status).as("신고자 탈퇴가 이기면 401").isEqualTo(401);
		assertThat(신고행수()).isZero();
	}

	// 경우 B: 상대(판매자)가 탈퇴하는 중(회원 행 삭제 + 방의 판매자 칸 NULL이 아직 커밋 전)에 구매자가 신고.
	// 신고는 상대 회원 행을 건드리지 않으므로 상대 행에서는 안 기다린다. 방 행을 FK 검사하다가 탈퇴 커밋을 기다릴 뿐이고,
	// 커밋 뒤에도 방은 남아 있으니(판매자 칸만 NULL) 201이다. 즉 "상대 회원 행은 안 잡아도 된다"는 판단의 재현.
	@Test
	void 경우B_상대가_탈퇴하는_중에_신고해도_교착_없이_201이다() throws Exception {
		long sid = 판매자.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> {
					회원_행_잠그기(sid);
					탈퇴_나머지(sid); // 방의 판매자 칸을 NULL로 바꾼 상태로 커밋 전까지 방 행을 쥔다
				},
				() -> 신고하기(구매자쿠키),
				() -> {
				});

		assertThat(status).as("상대 탈퇴가 신고를 막지 않는다").isEqualTo(201);
		assertThat(신고행수()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from conversations where id = ? and seller_id is null", Integer.class, 방id))
				.as("방은 남고 판매자 칸만 NULL").isEqualTo(1);
	}

	// 경우 C(반대 순서): 신고가 진행 중일 때 신고자의 탈퇴가 시작된다. (결과만 확인한다: lockForKeyShare 줄이 없어도 테스트 DB에서는
	// FK 검사가 신고자 회원 행부터라 같은 결과가 나온다. 즉 이 테스트는 그 줄을 직접 지키지 못한다.)
	// 탈퇴는 첫 잠금(회원 행 FOR UPDATE)에서 신고가 끝나기를 기다렸다 정상 완료(204)해야 하고, 신고는 201이어야 한다. 교착·500 없음.
	// 재현: 다른 트랜잭션이 방 행을 FOR UPDATE로 잡아 둔다 -> 신고가 회원 행 잠금을 잡은 뒤 INSERT(FK 검사)에서 기다린다
	//       -> 탈퇴 요청을 보낸다(회원 행에서 기다림) -> 방 행 잠금 해제 -> 신고 201 -> 탈퇴 204.
	@Test
	void 경우C_신고가_회원_행을_먼저_잡은_뒤_신고자_탈퇴가_시작되면_신고_201_탈퇴_204이고_reporter만_NULL이다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			@SuppressWarnings("unchecked")
			Future<Integer>[] holder = new Future[2];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				jdbc.queryForList("select id from conversations where id = ? for update", Long.class, 방id);
				holder[0] = pool.submit(() -> 신고하기(구매자쿠키));
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 1); // 신고가 방 행에서 기다리는 중 (이 시점에 신고자 회원 행 잠금도 이미 잡은 상태가 된다. 잠금이 줄 때문인지 FK 검사 때문인지는 구분하지 못한다)
					holder[1] = pool.submit(() -> 탈퇴(구매자쿠키));
					LockRaceSupport.락대기_기다리기(jdbc, 2); // 탈퇴도 기다리는 중
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertThat(holder[0].isDone()).isFalse();
				assertThat(holder[1].isDone()).isFalse();
			}); // 커밋(방 행 잠금 해제) -> 신고가 끝나고, 그 뒤 탈퇴가 진행된다
			assertThat(holder[0].get(10, TimeUnit.SECONDS)).as("신고").isEqualTo(201);
			assertThat(holder[1].get(10, TimeUnit.SECONDS)).as("탈퇴").isEqualTo(204);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertThat(jdbc.queryForObject("select count(*) from reports where conversation_id = ? and reporter_id is null",
				Integer.class, 방id)).as("신고는 남고 신고자 칸만 NULL").isEqualTo(1);
	}

	// 경우 D: 신고가 방 행에서 기다리는 중에 "상대"(판매자)의 탈퇴가 시작된다. 둘 다 교착 없이 끝난다(신고 201, 탈퇴 204).
	@Test
	void 경우D_신고가_진행_중일_때_상대_탈퇴가_시작돼도_신고_201_탈퇴_204이다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			@SuppressWarnings("unchecked")
			Future<Integer>[] holder = new Future[2];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				jdbc.queryForList("select id from conversations where id = ? for update", Long.class, 방id);
				holder[0] = pool.submit(() -> 신고하기(구매자쿠키));
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 1);
					holder[1] = pool.submit(() -> 탈퇴(판매자쿠키));
					LockRaceSupport.락대기_기다리기(jdbc, 2);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
			});
			assertThat(holder[0].get(10, TimeUnit.SECONDS)).as("신고").isEqualTo(201);
			assertThat(holder[1].get(10, TimeUnit.SECONDS)).as("상대 탈퇴").isEqualTo(204);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertThat(신고행수()).isEqualTo(1);
	}

	// 경우 E: 잠금 흉내 없이 실제로 동시에 출발시켜 여러 번 겹쳐 본다. 어느 쪽이 이기든 신고는 201 또는 401, 탈퇴는 204이고 500·교착은 없다.
	@Test
	void 경우E_신고와_신고자_탈퇴를_실제로_동시에_여러_번_돌려도_500이_없다() throws Exception {
		for (int i = 0; i < 8; i++) {
			User s = TestLogin.인증된_사용자(userRepository, jdbc, "rrs" + i, "연세대학교");
			User b = TestLogin.인증된_사용자(userRepository, jdbc, "rrb" + i, "연세대학교");
			Cookie sc = TestLogin.쿠키(tokenService, s);
			Cookie bc = TestLogin.쿠키(tokenService, b);
			long room = ConversationReportFixture.글과_방_만들기(mockMvc, om, sc, bc, "물건" + i)[1];

			ExecutorService pool = Executors.newFixedThreadPool(2);
			CountDownLatch ready = new CountDownLatch(2);
			CountDownLatch go = new CountDownLatch(1);
			try {
				Future<Integer> report = pool.submit(() -> {
					ready.countDown();
					go.await();
					return 신고(mockMvc, bc, room, 본문("SPAM")).getStatus();
				});
				Future<Integer> leave = pool.submit(() -> {
					ready.countDown();
					go.await();
					return 탈퇴(bc);
				});
				ready.await();
				go.countDown();
				assertThat(report.get(15, TimeUnit.SECONDS)).as("신고 #" + i).isIn(201, 401);
				assertThat(leave.get(15, TimeUnit.SECONDS)).as("탈퇴 #" + i).isEqualTo(204);
			} finally {
				pool.shutdownNow();
			}
		}
	}
}
