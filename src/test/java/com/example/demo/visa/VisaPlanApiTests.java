package com.example.demo.visa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

// S12-a·b 완료 조건 검사: 내 비자 일정 조회·등록(덮어쓰기)·삭제 (/users/me/visa-plan), 탈퇴 연동(S12-a),
// 응답의 daysLeft·stage·todos 계산(S12-b).
// 시계(Clock)는 가짜로 바꿔 "오늘"을 서울 2026-11-09로 고정한다(후기 테스트와 같은 설정 -> 스프링 컨텍스트를 같이 쓴다).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class VisaPlanApiTests {

	private static final String URL = "/users/me/visa-plan";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager; // 경쟁 테스트에서 회원 행을 잠그는 트랜잭션용
	@MockitoBean Clock clock;

	User 회원; // 연세대 인증 회원
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		// 서울 2026-11-09 12:00
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-09T03:00:00Z"));
		Mockito.when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "planuser", "연세대학교");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
	}

	@AfterEach
	void 정리() {
		// 일정이 회원보다 먼저(FK 순서)
		jdbc.update("delete from visa_plans");
		jdbc.update("delete from visa_reviews");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private static String 본문(String date) {
		return "{\"expiryDate\":\"" + date + "\"}";
	}

	private MockHttpServletResponse 조회(Cookie c) throws Exception {
		MockHttpServletRequestBuilder b = get(URL);
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private MockHttpServletResponse 저장(String json, Cookie c) throws Exception {
		MockHttpServletRequestBuilder b = put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private MockHttpServletResponse 삭제(Cookie c) throws Exception {
		MockHttpServletRequestBuilder b = delete(URL).with(csrf());
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private JsonNode json(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	private long 행수() {
		return jdbc.queryForObject("select count(*) from visa_plans", Long.class);
	}

	// ============ 등록·조회 ============

	@Test
	void PUT_후_GET하면_같은_값이_나온다() throws Exception {
		MockHttpServletResponse put = 저장(본문("2026-12-31"), 쿠키);
		assertThat(put.getStatus()).isEqualTo(200);
		assertThat(json(put).get("expiryDate").asString()).isEqualTo("2026-12-31");

		MockHttpServletResponse got = 조회(쿠키);
		assertThat(got.getStatus()).isEqualTo(200);
		assertThat(json(got).get("expiryDate").asString()).isEqualTo("2026-12-31");
	}

	@Test
	void PUT을_두_번_하면_행은_1개이고_두_번째_값이_남는다() throws Exception {
		assertThat(저장(본문("2026-12-31"), 쿠키).getStatus()).isEqualTo(200);
		assertThat(저장(본문("2027-03-15"), 쿠키).getStatus()).isEqualTo(200);

		assertThat(행수()).isEqualTo(1);
		assertThat(json(조회(쿠키)).get("expiryDate").asString()).isEqualTo("2027-03-15");
	}

	@Test
	void 같은_회원의_PUT_두_개가_동시에_와도_500이_없고_행은_1개다() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Callable<Integer> a = () -> {
				start.await();
				return 저장(본문("2026-12-31"), 쿠키).getStatus();
			};
			Callable<Integer> b = () -> {
				start.await();
				return 저장(본문("2027-01-31"), 쿠키).getStatus();
			};
			List<Future<Integer>> results = List.of(pool.submit(a), pool.submit(b));
			start.countDown();
			for (Future<Integer> f : results) {
				assertThat(f.get()).isEqualTo(200);
			}
		} finally {
			pool.shutdownNow();
		}
		assertThat(행수()).isEqualTo(1);
	}

	@Test
	void 일정이_없을_때_GET은_200과_null이다() throws Exception {
		MockHttpServletResponse res = 조회(쿠키);
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).get("expiryDate").isNull()).isTrue();
	}

	@Test
	void 학교_인증을_안_한_회원은_PUT이_403이고_GET은_200_null이다() throws Exception {
		User 미인증 = TestLogin.사용자(userRepository, "unverified");
		Cookie c = TestLogin.쿠키(tokenService, 미인증);
		assertThat(저장(본문("2026-12-31"), c).getStatus()).isEqualTo(403);
		assertThat(행수()).isZero();
		MockHttpServletResponse got = 조회(c);
		assertThat(got.getStatus()).isEqualTo(200);
		assertThat(json(got).get("expiryDate").isNull()).isTrue();
	}

	// ============ 비로그인·유령 토큰 ============

	@Test
	void 비로그인은_GET_PUT_DELETE_모두_401이다() throws Exception {
		assertThat(조회(null).getStatus()).isEqualTo(401);
		assertThat(저장(본문("2026-12-31"), null).getStatus()).isEqualTo(401);
		assertThat(삭제(null).getStatus()).isEqualTo(401);
	}

	@Test
	void 유령_토큰은_GET_PUT_DELETE_모두_401이다() throws Exception {
		Cookie 유령 = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(조회(유령).getStatus()).isEqualTo(401);
		assertThat(저장(본문("2026-12-31"), 유령).getStatus()).isEqualTo(401);
		assertThat(삭제(유령).getStatus()).isEqualTo(401);
		assertThat(행수()).isZero();
	}

	@Test
	void CSRF_헤더_없는_PUT은_403이다() throws Exception {
		int status = mockMvc.perform(put(URL).cookie(쿠키).contentType(MediaType.APPLICATION_JSON).content(본문("2026-12-31")))
				.andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(403);
	}

	// ============ 날짜 검증 (오늘 = 서울 2026-11-09) ============

	@Test
	void 어제는_400이고_오늘은_200이다() throws Exception {
		assertThat(저장(본문("2026-11-08"), 쿠키).getStatus()).isEqualTo(400);
		assertThat(행수()).isZero();
		assertThat(저장(본문("2026-11-09"), 쿠키).getStatus()).isEqualTo(200);
	}

	@Test
	void 오늘_플러스_5년은_200이고_하루_더_먼_날짜는_400이다() throws Exception {
		assertThat(저장(본문("2031-11-10"), 쿠키).getStatus()).isEqualTo(400);
		assertThat(저장(본문("2031-11-09"), 쿠키).getStatus()).isEqualTo(200);
		assertThat(json(조회(쿠키)).get("expiryDate").asString()).isEqualTo("2031-11-09");
	}

	@Test
	void 날짜_형식이_아니거나_빈_값이면_400이다() throws Exception {
		assertThat(저장(본문("not-a-date"), 쿠키).getStatus()).isEqualTo(400);
		assertThat(저장(본문("2026-13-45"), 쿠키).getStatus()).isEqualTo(400);
		assertThat(저장("{\"expiryDate\":\"\"}", 쿠키).getStatus()).isEqualTo(400);
		assertThat(저장("{\"expiryDate\":null}", 쿠키).getStatus()).isEqualTo(400);
		assertThat(저장("{}", 쿠키).getStatus()).isEqualTo(400);
		assertThat(행수()).isZero();
	}

	// ============ 삭제 ============

	@Test
	void 일정이_없으면_DELETE는_404다() throws Exception {
		assertThat(삭제(쿠키).getStatus()).isEqualTo(404);
	}

	@Test
	void 일정이_있으면_DELETE는_204이고_이후_GET은_null이다() throws Exception {
		저장(본문("2026-12-31"), 쿠키);
		assertThat(삭제(쿠키).getStatus()).isEqualTo(204);
		assertThat(행수()).isZero();
		assertThat(json(조회(쿠키)).get("expiryDate").isNull()).isTrue();
	}

	@Test
	void 미인증_회원도_DELETE는_인증_검사_없이_404까지_간다() throws Exception {
		Cookie c = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "unverified2"));
		assertThat(삭제(c).getStatus()).isEqualTo(404);
	}

	// ============ 추가 검증: 회원 간 분리 ============

	@Test
	void A가_DELETE해도_B의_일정은_남고_GET은_내_것만_준다() throws Exception {
		User 다른회원 = TestLogin.인증된_사용자(userRepository, jdbc, "planother", "연세대학교");
		Cookie 다른쿠키 = TestLogin.쿠키(tokenService, 다른회원);
		assertThat(저장(본문("2026-12-31"), 쿠키).getStatus()).isEqualTo(200);
		assertThat(저장(본문("2027-06-30"), 다른쿠키).getStatus()).isEqualTo(200);
		assertThat(행수()).isEqualTo(2);

		// 각자 자기 값만 보인다
		assertThat(json(조회(쿠키)).get("expiryDate").asString()).isEqualTo("2026-12-31");
		assertThat(json(조회(다른쿠키)).get("expiryDate").asString()).isEqualTo("2027-06-30");

		// A가 지워도 B 것은 그대로
		assertThat(삭제(쿠키).getStatus()).isEqualTo(204);
		assertThat(행수()).isEqualTo(1);
		assertThat(json(조회(쿠키)).get("expiryDate").isNull()).isTrue();
		assertThat(json(조회(다른쿠키)).get("expiryDate").asString()).isEqualTo("2027-06-30");

		// B가 덮어써도 A(없음)에 영향 없음: 행은 여전히 1개
		assertThat(저장(본문("2027-07-01"), 다른쿠키).getStatus()).isEqualTo(200);
		assertThat(행수()).isEqualTo(1);
	}

	@Test
	void 다른_회원의_일정이_있어도_내_일정이_없으면_DELETE는_404다() throws Exception {
		User 다른회원 = TestLogin.인증된_사용자(userRepository, jdbc, "planother2", "연세대학교");
		저장(본문("2026-12-31"), TestLogin.쿠키(tokenService, 다른회원));
		assertThat(삭제(쿠키).getStatus()).isEqualTo(404);
		assertThat(행수()).isEqualTo(1);
	}

	// ============ 추가 검증: 서울 날짜 경계 ============
	// 같은 컨텍스트에서 가짜 시계의 응답만 다시 정해 시간을 옮긴다(@MockitoBean 설정은 그대로).

	@Test
	void 서울_자정_직후_UTC로는_전날이어도_오늘은_서울_날짜다() throws Exception {
		// UTC 2026-11-09 15:30 = 서울 2026-11-10 00:30
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-09T15:30:00Z"));
		// UTC 날짜(11-09)를 오늘로 잘못 쓰면 200이 되고, 서울 날짜(11-10) 기준이면 어제라 400
		assertThat(저장(본문("2026-11-09"), 쿠키).getStatus()).isEqualTo(400);
		assertThat(저장(본문("2026-11-10"), 쿠키).getStatus()).isEqualTo(200);
		// 5년 경계도 서울 날짜 기준: 2031-11-10 가능, 2031-11-11 불가
		assertThat(저장(본문("2031-11-11"), 쿠키).getStatus()).isEqualTo(400);
		assertThat(저장(본문("2031-11-10"), 쿠키).getStatus()).isEqualTo(200);
	}

	@Test
	void 서울_날짜가_UTC보다_하루_앞서도_UTC_오늘이_서울_어제로_거절된다() throws Exception {
		// UTC 2026-11-08 15:30 = 서울 2026-11-09 00:30 (UTC 날짜는 11-08)
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-08T15:30:00Z"));
		assertThat(저장(본문("2026-11-08"), 쿠키).getStatus()).isEqualTo(400);
		assertThat(저장(본문("2026-11-09"), 쿠키).getStatus()).isEqualTo(200);
	}

	// ============ 추가 검증: 이상한 날짜 형태 ============

	@Test
	// 숫자 날짜는 Jackson이 "1970-01-01부터의 일수"로 읽어 받아들인다(알려진 한계, 나중에 막을 예정).
	// 그래서 0은 1970-01-01로 읽혀 "오늘 이전" 범위 검사에서 400이 난다. 숫자가 받아지는 동작 자체는 여기서 단언하지 않는다.
	void 숫자_0은_1970년으로_읽혀_범위_밖이라_400이다() throws Exception {
		assertThat(저장("{\"expiryDate\":0}", 쿠키).getStatus()).isEqualTo(400);
		assertThat(행수()).isZero();
	}

	// ============ 탈퇴 연동 ============

	@Test
	void 회원이_탈퇴하면_일정_행도_사라진다() throws Exception {
		저장(본문("2026-12-31"), 쿠키);
		assertThat(행수()).isEqualTo(1);
		// 탈퇴는 비밀번호 확인이 필요하다. 도우미 회원의 비밀번호는 {noop}unused-password 라서 "unused-password"로 확인한다
		int status = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(204);
		assertThat(userRepository.findById(회원.getId())).isEmpty();
		assertThat(행수()).isZero();
	}

	// ============ 탈퇴 경쟁 ============

	@Test
	void 회원_조회_뒤_저장_전에_탈퇴가_커밋되면_PUT은_401이고_일정_행이_없다() throws Exception {
		// 일정이 "없는" 회원이어야 한다: upsert가 새로 INSERT할 때만 FK 검사가 회원 행 잠금을 기다린다(UPDATE는 안 기다림)
		Long uid = 회원.getId();
		int status = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				// (1) 회원 행을 잠가 둔다 -> PUT의 INSERT가 FK 검사에서 여기서 대기
				() -> jdbc.queryForList("select id from users where id = ? for update", Long.class, uid),
				// (2) 별도 스레드에서 PUT
				() -> 저장(본문("2026-12-31"), 쿠키).getStatus(),
				// (3) 잠근 쪽이 회원을 지우고 커밋 -> 깨어난 PUT은 fk_visa_plans_user 위반 -> 401
				() -> jdbc.update("delete from users where id = ?", uid));
		assertThat(status).isEqualTo(401);
		assertThat(행수()).isZero();
	}

	// ============ S12-b: daysLeft · stage · todos ============

	// 서울 오늘(2026-11-09)에서 n일 뒤 날짜 문자열
	private static String 오늘_플러스(int n) {
		return java.time.LocalDate.of(2026, 11, 9).plusDays(n).toString();
	}

	@Test
	void 만료일이_오늘_플러스_200이면_할일_3개_오름차순_단계는_null이다() throws Exception {
		저장(본문(오늘_플러스(200)), 쿠키);
		JsonNode body = json(조회(쿠키));
		assertThat(body.get("daysLeft").asInt()).isEqualTo(200);
		assertThat(body.get("stage").isNull()).isTrue();
		JsonNode todos = body.get("todos");
		assertThat(todos.size()).isEqualTo(3);
		// 만료일 - 120/30/7일, 이른 날짜부터
		java.time.LocalDate 만료 = java.time.LocalDate.parse(오늘_플러스(200));
		assertThat(todos.get(0).get("dueDate").asString()).isEqualTo(만료.minusDays(120).toString());
		assertThat(todos.get(1).get("dueDate").asString()).isEqualTo(만료.minusDays(30).toString());
		assertThat(todos.get(2).get("dueDate").asString()).isEqualTo(만료.minusDays(7).toString());
		assertThat(todos.get(0).get("code").asString()).isEqualTo("PREPARE");
		assertThat(todos.get(1).get("code").asString()).isEqualTo("CHECK");
		assertThat(todos.get(2).get("code").asString()).isEqualTo("URGENT");
		// 각 할 일에 code·title·dueDate·daysLeft가 모두 있고, 오늘 기준 남은 일수가 맞다(80, 170, 193)
		for (JsonNode t : todos) {
			assertThat(t.get("code").asString()).isNotBlank();
			assertThat(t.get("title").asString()).isNotBlank();
			assertThat(t.hasNonNull("dueDate")).isTrue();
			assertThat(t.hasNonNull("daysLeft")).isTrue();
		}
		assertThat(todos.get(0).get("daysLeft").asInt()).isEqualTo(80);
		assertThat(todos.get(1).get("daysLeft").asInt()).isEqualTo(170);
		assertThat(todos.get(2).get("daysLeft").asInt()).isEqualTo(193);
	}

	@Test
	void 남은_일수별_단계_경계가_맞다() throws Exception {
		// {남은 일수, 기대 단계(null이면 단계 없음)}
		Object[][] 경우 = {
				{121, null}, {120, "PREPARE"}, {31, "PREPARE"}, {30, "CHECK"}, {8, "CHECK"}, {7, "URGENT"}, {0, "URGENT"} };
		for (Object[] c : 경우) {
			int n = (Integer) c[0];
			assertThat(저장(본문(오늘_플러스(n)), 쿠키).getStatus()).isEqualTo(200);
			JsonNode body = json(조회(쿠키));
			assertThat(body.get("daysLeft").asInt()).as("daysLeft %d", n).isEqualTo(n);
			if (c[1] == null) {
				assertThat(body.get("stage").isNull()).as("stage %d", n).isTrue();
			} else {
				assertThat(body.get("stage").asString()).as("stage %d", n).isEqualTo(c[1]);
			}
		}
	}

	@Test
	void 만료일이_지나면_EXPIRED이고_daysLeft와_지난_할일은_음수다() throws Exception {
		// 저장은 오늘 이후만 받으므로 jdbc로 어제 날짜를 직접 넣는다(시간이 흘러 지난 날짜가 된 상황)
		jdbc.update("insert into visa_plans (user_id, expiry_date, created_at, updated_at) values (?, ?, now(), now())", 회원.getId(),
				java.sql.Date.valueOf("2026-11-08"));
		JsonNode body = json(조회(쿠키));
		assertThat(body.get("stage").asString()).isEqualTo("EXPIRED");
		assertThat(body.get("daysLeft").asInt()).isEqualTo(-1);
		// 지난 할 일도 목록에서 빠지지 않고 daysLeft가 음수
		assertThat(body.get("todos").size()).isEqualTo(3);
		for (JsonNode t : body.get("todos")) {
			assertThat(t.get("daysLeft").asInt()).isNegative();
		}
	}

	@Test
	void 저장_뒤_시계를_앞으로_돌리면_EXPIRED가_된다() throws Exception {
		assertThat(저장(본문("2026-11-20"), 쿠키).getStatus()).isEqualTo(200);
		// 서울 2026-11-25 12:00
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-25T03:00:00Z"));
		JsonNode body = json(조회(쿠키));
		assertThat(body.get("stage").asString()).isEqualTo("EXPIRED");
		assertThat(body.get("daysLeft").asInt()).isEqualTo(-5);
	}

	@Test
	void 서울_00시30분이면_daysLeft는_서울_날짜_기준이다() throws Exception {
		assertThat(저장(본문("2026-11-20"), 쿠키).getStatus()).isEqualTo(200);
		// UTC 2026-11-09 15:30 = 서울 2026-11-10 00:30. UTC 날짜(11-09)로 세면 11, 서울(11-10)이면 10
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-09T15:30:00Z"));
		assertThat(json(조회(쿠키)).get("daysLeft").asInt()).isEqualTo(10);
	}

	@Test
	void 일정이_없으면_네_키가_있고_daysLeft_stage는_null_todos는_빈_배열이다() throws Exception {
		JsonNode body = json(조회(쿠키));
		// get(...)이 null이면 "키 없음", NullNode면 "null로 옴" -> has()로 키 존재를 따로 확인
		for (String k : new String[] { "expiryDate", "daysLeft", "stage", "todos" }) {
			assertThat(body.has(k)).as("키 %s", k).isTrue();
		}
		assertThat(body.get("expiryDate").isNull()).isTrue();
		assertThat(body.get("daysLeft").isNull()).isTrue();
		assertThat(body.get("stage").isNull()).isTrue();
		assertThat(body.get("todos").isArray()).isTrue();
		assertThat(body.get("todos").size()).isZero();
	}

	@Test
	void PUT_응답도_네_키와_같은_값을_가진다() throws Exception {
		JsonNode body = json(저장(본문(오늘_플러스(25)), 쿠키));
		for (String k : new String[] { "expiryDate", "daysLeft", "stage", "todos" }) {
			assertThat(body.has(k)).as("키 %s", k).isTrue();
		}
		assertThat(body.get("expiryDate").asString()).isEqualTo(오늘_플러스(25));
		assertThat(body.get("daysLeft").asInt()).isEqualTo(25);
		assertThat(body.get("stage").asString()).isEqualTo("CHECK");
		assertThat(body.get("todos").size()).isEqualTo(3);
		// 200일 남은 경우: 단계 없음은 키는 있고 값만 null
		JsonNode far = json(저장(본문(오늘_플러스(200)), 쿠키));
		assertThat(far.has("stage")).isTrue();
		assertThat(far.get("stage").isNull()).isTrue();
		assertThat(far.get("daysLeft").asInt()).isEqualTo(200);
	}

	@Test
	void 만료_전이어도_지난_할일은_빠지지_않고_음수_daysLeft로_온다() throws Exception {
		// 남은 일수 25 -> PREPARE 시점(만료-120)은 95일 지났고, CHECK 시점(만료-30)도 5일 지났다
		저장(본문(오늘_플러스(25)), 쿠키);
		JsonNode todos = json(조회(쿠키)).get("todos");
		assertThat(todos.size()).isEqualTo(3);
		assertThat(todos.get(0).get("daysLeft").asInt()).isEqualTo(-95); // 25-120
		assertThat(todos.get(1).get("daysLeft").asInt()).isEqualTo(-5);  // 25-30
		assertThat(todos.get(2).get("daysLeft").asInt()).isEqualTo(18);  // 25-7
		assertThat(todos.get(0).get("dueDate").asString()).isEqualTo(오늘_플러스(-95));
	}

	@Test
	void 만료일이_지난_뒤_할일_값이_정확하다() throws Exception {
		jdbc.update("insert into visa_plans (user_id, expiry_date, created_at, updated_at) values (?, ?, now(), now())", 회원.getId(),
				java.sql.Date.valueOf("2026-11-08")); // daysLeft -1
		JsonNode todos = json(조회(쿠키)).get("todos");
		assertThat(todos.get(0).get("daysLeft").asInt()).isEqualTo(-121);
		assertThat(todos.get(1).get("daysLeft").asInt()).isEqualTo(-31);
		assertThat(todos.get(2).get("daysLeft").asInt()).isEqualTo(-8);
		for (JsonNode t : todos) {
			assertThat(t.get("code").asString()).isNotBlank();
			assertThat(t.get("title").asString()).isNotBlank();
			assertThat(t.hasNonNull("dueDate")).isTrue();
		}
	}

	@Test
	void 서울_00시30분에도_할일_daysLeft는_서울_날짜_기준이다() throws Exception {
		assertThat(저장(본문("2026-12-31"), 쿠키).getStatus()).isEqualTo(200);
		// 서울 2026-11-10 00:30 -> 만료까지 51일, CHECK 할 일(12-01)까지 21일
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-09T15:30:00Z"));
		JsonNode body = json(조회(쿠키));
		assertThat(body.get("daysLeft").asInt()).isEqualTo(51);
		assertThat(body.get("todos").get(1).get("daysLeft").asInt()).isEqualTo(21);
		assertThat(body.get("stage").asString()).isEqualTo("PREPARE");
	}
}
