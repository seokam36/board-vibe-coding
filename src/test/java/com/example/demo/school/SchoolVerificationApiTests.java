package com.example.demo.school;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S10-a 완료 조건 3~6: 응답의 verifiedSchool, GET /schools의 emailDomains, 글 응답 school 모양, 쿼리 수를 API로 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SchoolVerificationApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired StringRedisTemplate redis;
	@Autowired EntityManagerFactory entityManagerFactory;

	private final ObjectMapper om = new ObjectMapper();
	private static final String PW = "password123";

	@BeforeEach
	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
		for (String pattern : new String[] { "refresh:*", "user-refresh:*" }) {
			Set<String> keys = redis.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}

	private JsonNode 본문(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	private void 가입(String username) throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"nickname\":\"" + username + "\",\"password\":\"" + PW + "\"}"))
				.andReturn();
	}

	private MockHttpServletResponse 로그인(String username) throws Exception {
		return mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + PW + "\"}")).andReturn().getResponse();
	}

	private MockHttpServletResponse 내정보(Cookie access) throws Exception {
		return mockMvc.perform(get("/auth/me").cookie(access)).andReturn().getResponse();
	}

	private void 연세대로_인증(String username) {
		jdbc.update("update users set verified_school_id = (select id from schools where name = '연세대학교') where username = ?",
				username);
	}

	private void 연세대_검사(JsonNode user, String 어디) {
		JsonNode v = user.get("verifiedSchool");
		assertThat(v).as(어디 + " verifiedSchool 키").isNotNull();
		assertThat(v.isNull()).as(어디 + " verifiedSchool이 null이면 안 됨").isFalse();
		assertThat(v.get("name").asString()).as(어디).isEqualTo("연세대학교");
		assertThat(v.get("id").asLong()).as(어디).isEqualTo(
				jdbc.queryForObject("select id from schools where name = '연세대학교'", Long.class));
	}

	// ---------- 완료 조건 3 ----------

	@Test
	void 미인증_회원의_auth_me는_verifiedSchool이_null이다() throws Exception {
		User u = TestLogin.사용자(userRepository, "plain");
		JsonNode body = 본문(내정보(TestLogin.쿠키(tokenService, u)));
		assertThat(body.has("verifiedSchool")).as("verifiedSchool 키가 있어야 함").isTrue();
		assertThat(body.get("verifiedSchool").isNull()).isTrue();
	}

	@Test
	void 인증된_회원의_auth_me는_verifiedSchool에_연세대를_담는다() throws Exception {
		User u = TestLogin.인증된_사용자(userRepository, jdbc, "verified", "연세대학교");
		연세대_검사(본문(내정보(TestLogin.쿠키(tokenService, u))), "/auth/me");
	}

	@Test
	void 미인증_회원의_로그인_응답은_verifiedSchool이_null이다() throws Exception {
		가입("plain2");
		JsonNode body = 본문(로그인("plain2"));
		assertThat(body.has("verifiedSchool")).isTrue();
		assertThat(body.get("verifiedSchool").isNull()).isTrue();
	}

	@Test
	void 인증된_회원의_로그인_응답에_연세대가_담긴다() throws Exception {
		가입("ver2");
		연세대로_인증("ver2");
		연세대_검사(본문(로그인("ver2")), "login");
	}

	@Test
	void 인증된_회원의_재발급_응답에_연세대가_담긴다() throws Exception {
		가입("ver3");
		연세대로_인증("ver3");
		String refresh = 로그인("ver3").getCookie("refresh_token").getValue();
		MockHttpServletResponse res = mockMvc
				.perform(post("/auth/refresh").with(csrf()).cookie(new Cookie("refresh_token", refresh))).andReturn()
				.getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		연세대_검사(본문(res), "refresh");
	}

	@Test
	void 인증된_회원의_닉네임_변경_응답에_연세대가_담긴다() throws Exception {
		가입("ver4");
		연세대로_인증("ver4");
		Cookie access = new Cookie("access_token", 로그인("ver4").getCookie("access_token").getValue());
		MockHttpServletResponse res = mockMvc.perform(put("/users/me/nickname").with(csrf()).cookie(access)
				.contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"newnick\"}")).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		연세대_검사(본문(res), "닉네임 변경");
	}

	@Test
	void 미인증_회원의_닉네임_변경_응답은_verifiedSchool이_null이다() throws Exception {
		가입("plain5");
		Cookie access = new Cookie("access_token", 로그인("plain5").getCookie("access_token").getValue());
		JsonNode body = 본문(mockMvc.perform(put("/users/me/nickname").with(csrf()).cookie(access)
				.contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"newnick5\"}")).andReturn().getResponse());
		assertThat(body.has("verifiedSchool")).isTrue();
		assertThat(body.get("verifiedSchool").isNull()).isTrue();
	}

	// ---------- 완료 조건 4 ----------

	@Test
	void 학교_목록_각_항목에_emailDomains가_있고_성균관대는_정렬된_2개다() throws Exception {
		JsonNode arr = 본문(mockMvc.perform(get("/schools")).andReturn().getResponse());
		List<String> names = new ArrayList<>();
		for (JsonNode s : arr) {
			String name = s.get("name").asString();
			names.add(name);
			assertThat(s.has("emailDomains")).as(name + " emailDomains").isTrue();
			assertThat(s.get("emailDomains").isArray()).isTrue();
			assertThat(s.get("emailDomains").size()).isGreaterThanOrEqualTo(1);
			if (name.equals("성균관대학교")) {
				// 정렬: g.skku.edu 가 skku.edu 보다 앞
				assertThat(s.get("emailDomains").size()).isEqualTo(2);
				assertThat(s.get("emailDomains").get(0).asString()).isEqualTo("g.skku.edu");
				assertThat(s.get("emailDomains").get(1).asString()).isEqualTo("skku.edu");
			}
			if (name.equals("연세대학교")) {
				assertThat(s.get("emailDomains").get(0).asString()).isEqualTo("yonsei.ac.kr");
			}
		}
		assertThat(names).containsExactly("경희대학교", "고려대학교", "성균관대학교", "연세대학교", "한양대학교");
	}

	// ---------- 완료 조건 5 ----------

	@Test
	void 글_응답의_school은_id와_name만_갖고_emailDomains가_섞이지_않는다() throws Exception {
		User u = TestLogin.인증된_사용자(userRepository, jdbc, "author", "연세대학교");
		Cookie c = TestLogin.쿠키(tokenService, u);
		Long schoolId = jdbc.queryForObject("select id from schools where name = '연세대학교'", Long.class);
		MockHttpServletResponse created = mockMvc.perform(post("/posts").with(csrf()).cookie(c)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"t\",\"content\":\"c\",\"category\":\"CHAT\",\"schoolId\":" + schoolId + "}"))
				.andReturn().getResponse();
		assertThat(created.getStatus()).isEqualTo(201);
		long postId = 본문(created).get("id").asLong();

		JsonNode detail = 본문(mockMvc.perform(get("/posts/" + postId)).andReturn().getResponse());
		JsonNode list = 본문(mockMvc.perform(get("/posts")).andReturn().getResponse());
		JsonNode first = list.get(0); // 목록은 감싸지 않은 배열
		for (JsonNode p : new JsonNode[] { detail, first }) {
			JsonNode school = p.get("school");
			assertThat(school.get("name").asString()).isEqualTo("연세대학교");
			assertThat(school.has("emailDomains")).as("글 응답 school에 emailDomains 금지").isFalse();
			assertThat(school.size()).as("school 필드 수(id, name)").isEqualTo(2);
		}
	}

	// ---------- 완료 조건 6 ----------

	// 학교 수가 달라도 /schools 쿼리 수가 같아야 한다(학교마다 도메인을 따로 조회하는 N+1 방지)
	@Test
	void 학교_목록_조회의_쿼리_수는_학교_수에_비례하지_않는다() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			mockMvc.perform(get("/schools")).andReturn(); // 워밍업
			stats.clear();
			mockMvc.perform(get("/schools")).andReturn();
			long five = stats.getPrepareStatementCount();
			jdbc.update("insert into schools (name) values ('테스트대학교A'), ('테스트대학교B'), ('테스트대학교C')");
			try {
				jdbc.update("insert into school_email_domains (school_id, domain) select id, 'a.test.kr' from schools where name = '테스트대학교A'");
				stats.clear();
				mockMvc.perform(get("/schools")).andReturn();
				long eight = stats.getPrepareStatementCount();
				assertThat(eight).as("학교 8개 쿼리 수(%s) == 학교 5개 쿼리 수(%s)", eight, five).isEqualTo(five);
			} finally {
				jdbc.update("delete from school_email_domains where domain = 'a.test.kr'");
				jdbc.update("delete from schools where name like '테스트대학교_'");
			}
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}
}
