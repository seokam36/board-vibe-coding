package com.example.demo.visa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

// 리뷰 수정분 검사: 접수일 "최근 5년 이내", 처리 기간 "365일 이하" 규칙(작성·수정 둘 다)과 탈퇴 후 통계 유지.
// 컨텍스트 캐시를 같이 쓰도록 다른 후기 테스트와 같은 설정(@MockitoBean Clock)을 쓴다. "오늘"은 서울 2026-11-09 -> 5년 경계는 2021-11-09.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class VisaReviewDateLimitTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@MockitoBean Clock clock;

	User 회원;
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		Mockito.when(clock.instant()).thenReturn(Instant.parse("2026-11-09T03:00:00Z"));
		Mockito.when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "vuser", "연세대학교");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from visa_reviews"); // 후기가 회원보다 먼저(FK 순서)
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private static String 본문(String received, String result) {
		return "{\"visaType\":\"D_2\",\"applicationType\":\"EXTENSION\",\"receivedDate\":\"" + received
				+ "\",\"resultDate\":\"" + result + "\",\"result\":\"APPROVED\",\"content\":\"후기\"}";
	}

	private int 작성상태(String body, Cookie c) throws Exception {
		return mockMvc.perform(post("/visa-reviews").with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn().getResponse().getStatus();
	}

	private int 수정상태(long id, String body) throws Exception {
		return mockMvc.perform(put("/visa-reviews/" + id).with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content(body)).andReturn().getResponse().getStatus();
	}

	private long 후기수() {
		return jdbc.queryForObject("select count(*) from visa_reviews", Long.class);
	}

	// 정상 날짜(접수 2026-09-01, 결과 2026-10-03)의 후기를 DB에 바로 넣는다
	private long 저장(User author) {
		return jdbc.queryForObject("insert into visa_reviews (author_id, school_id, visa_type, application_type, received_date, "
				+ "result_date, result, content, created_at) values (?, (select id from schools where name = '연세대학교'), "
				+ "'D_2', 'EXTENSION', '2026-09-01', '2026-10-03', 'APPROVED', '시드', now()) returning id", Long.class, author.getId());
	}

	// ============ A. 작성(POST) 경계 ============

	@Test
	void 작성_접수일이_정확히_5년_전_당일이면_201이다() throws Exception {
		assertThat(작성상태(본문("2021-11-09", "2021-12-09"), 쿠키)).isEqualTo(201);
		assertThat(후기수()).isEqualTo(1);
	}

	@Test
	void 작성_접수일이_5년_전보다_하루_더_옛날이면_400이고_행이_안_생긴다() throws Exception {
		assertThat(작성상태(본문("2021-11-08", "2021-12-08"), 쿠키)).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	@Test
	void 작성_처리_기간이_정확히_365일이면_201이다() throws Exception {
		assertThat(작성상태(본문("2025-11-01", "2026-11-01"), 쿠키)).isEqualTo(201);
		assertThat(후기수()).isEqualTo(1);
	}

	@Test
	void 작성_처리_기간이_366일이면_400이고_행이_안_생긴다() throws Exception {
		assertThat(작성상태(본문("2025-11-01", "2026-11-02"), 쿠키)).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	@Test
	void 작성_접수일이_아주_옛날_연도면_400이고_행이_안_생긴다() throws Exception {
		// Jackson이 형식을 거부해도, 받아 준 뒤 서비스가 거부해도 모두 400이어야 한다
		assertThat(작성상태(본문("-10000-01-01", "2026-11-01"), 쿠키)).isEqualTo(400);
		assertThat(후기수()).isZero();
	}

	// ============ B. 수정(PUT) 경계 ============

	@Test
	void 수정_접수일을_5년_전보다_옛날로_고치면_400이고_DB값이_안_바뀐다() throws Exception {
		long id = 저장(회원);
		assertThat(수정상태(id, 본문("2021-11-08", "2021-12-08"))).isEqualTo(400);
		var row = jdbc.queryForMap("select received_date, result_date, content from visa_reviews where id = ?", id);
		assertThat(row.get("received_date").toString()).isEqualTo("2026-09-01");
		assertThat(row.get("result_date").toString()).isEqualTo("2026-10-03");
		assertThat(row.get("content")).isEqualTo("시드");
	}

	@Test
	void 수정_처리_기간을_366일로_고치면_400이고_DB값이_안_바뀐다() throws Exception {
		long id = 저장(회원);
		assertThat(수정상태(id, 본문("2025-11-01", "2026-11-02"))).isEqualTo(400);
		var row = jdbc.queryForMap("select received_date, result_date, content from visa_reviews where id = ?", id);
		assertThat(row.get("received_date").toString()).isEqualTo("2026-09-01");
		assertThat(row.get("result_date").toString()).isEqualTo("2026-10-03");
		assertThat(row.get("content")).isEqualTo("시드");
	}

	// ============ C. 탈퇴 후 통계 유지 ============

	// 같은 조합(연세대·D_2·EXTENSION) 후기 2건(10일, 20일)을 서로 다른 회원이 쓰고, 한 명이 탈퇴해도 통계는 그대로여야 한다
	@Test
	void 한_명이_탈퇴해도_통계의_건수와_평균_처리일수가_그대로다() throws Exception {
		User 둘째 = TestLogin.인증된_사용자(userRepository, jdbc, "vuser2", "연세대학교");
		Cookie 둘째쿠키 = TestLogin.쿠키(tokenService, 둘째);
		assertThat(작성상태(본문("2026-09-01", "2026-09-11"), 쿠키)).isEqualTo(201);
		assertThat(작성상태(본문("2026-09-01", "2026-09-21"), 둘째쿠키)).isEqualTo(201);

		JsonNode 전 = 연세_행(통계());
		assertThat(전.get("count").asLong()).isEqualTo(2);
		assertThat(전.get("avgDays").asDouble()).isEqualTo(15.0);

		int 탈퇴 = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();
		assertThat(탈퇴).isEqualTo(204);
		assertThat(userRepository.findById(회원.getId())).isEmpty();

		JsonNode 후 = 연세_행(통계());
		assertThat(후.get("count").asLong()).isEqualTo(2);
		assertThat(후.get("avgDays").asDouble()).isEqualTo(전.get("avgDays").asDouble());
	}

	private JsonNode 통계() throws Exception {
		MockHttpServletResponse res = mockMvc.perform(get("/visa-reviews/stats")).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		return om.readTree(res.getContentAsString());
	}

	private JsonNode 연세_행(JsonNode arr) {
		for (JsonNode n : arr) {
			if (n.get("school").get("name").asString().equals("연세대학교") && n.get("visaType").get("code").asString().equals("D_2")
					&& n.get("applicationType").get("code").asString().equals("EXTENSION")) {
				return n;
			}
		}
		throw new AssertionError("연세대 D_2 EXTENSION 통계 행이 없다: " + arr);
	}
}
