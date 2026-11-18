package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.permit.AdminPermitTestBase;
import com.example.demo.permit.FakePermitTextReader;
import com.example.demo.user.domain.User;

import jakarta.servlet.http.Cookie;

// S20-f1 보충 검사(tester): 메일 인증 API 인증/CSRF, 예전 메일 인증 회원의 다른 기능, 자동 승인, 탈퇴, 문서·Flyway 상태.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakePermitTextReader.Config.class })
@AutoConfigureMockMvc
class SchoolEmailRemovedExtraTests extends AdminPermitTestBase {

	@org.springframework.beans.factory.annotation.Autowired FakePermitTextReader 판독기;

	// 다른 표(시장 글·비자 후기·쪽지)에 남은 행이 회원 삭제를 막지 않게 먼저 비운다(부모 @AfterEach보다 먼저 실행됨)
	@AfterEach
	void 추가_정리() {
		jdbc.update("delete from messages");
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_item_photos");
		jdbc.update("delete from market_items");
		jdbc.update("delete from visa_reviews");
	}

	private MockHttpServletResponse 보내기(String url, Cookie c, String json) throws Exception {
		var b = post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json);
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private long 글쓰기_id(User u) throws Exception {
		MockHttpServletResponse r = 보내기("/posts", 쿠키(u),
				"{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 연세id + ",\"category\":\"CHAT\"}");
		assertThat(r.getStatus()).as("글쓰기").isEqualTo(201);
		return json(r).get("id").asLong();
	}

	private Map<String, Object> 인증칸(User u) {
		return jdbc.queryForMap("select verified_school_id, verified_at, school_email_hash from users where id = ?", u.getId());
	}

	// 완료 조건 1: 비로그인 401, CSRF 없음 403 (두 주소 모두). 인증 칸은 그대로
	@Test
	void 메일_인증_API는_비로그인이면_401이고_CSRF가_없으면_403이다() throws Exception {
		User u = 회원("mailauth");
		for (String url : new String[] { "/users/me/school-verification/code", "/users/me/school-verification" }) {
			// 비로그인(CSRF는 있음): 보호된 주소가 아니라 없는 주소여도 인증이 먼저라 401
			assertThat(보내기(url, null, "{}").getStatus()).as(url + " 비로그인").isEqualTo(401);
			// 로그인했지만 CSRF 없음 -> 403
			int s = mockMvc.perform(post(url).cookie(쿠키(u)).contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andReturn().getResponse().getStatus();
			assertThat(s).as(url + " CSRF 없음").isEqualTo(403);
		}
		assertThat(인증칸(u).values()).allMatch(v -> v == null);
	}

	// 완료 조건 3: 예전 메일 인증 회원이 댓글·공감·중고 글·비자 후기를 쓸 수 있다
	@Test
	void 예전_메일_인증_회원은_댓글_공감_중고글_비자후기도_된다() throws Exception {
		User u = TestLogin.인증된_사용자(userRepository, jdbc, "oldmail2", "연세대학교");
		long postId = 글쓰기_id(u);

		assertThat(보내기("/posts/" + postId + "/comments", 쿠키(u), "{\"content\":\"댓글\"}").getStatus()).as("댓글").isEqualTo(201);
		assertThat(보내기("/posts/" + postId + "/likes", 쿠키(u), "").getStatus()).as("공감").isIn(200, 201, 204);
		assertThat(보내기("/market-items", 쿠키(u), "{\"title\":\"팝니다\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}")
				.getStatus()).as("중고 글").isEqualTo(201);
		String today = java.time.LocalDate.now().minusDays(1).toString();
		String twoDaysAgo = java.time.LocalDate.now().minusDays(3).toString();
		assertThat(보내기("/visa-reviews", 쿠키(u), "{\"visaType\":\"D_2\",\"applicationType\":\"EXTENSION\",\"receivedDate\":\""
				+ twoDaysAgo + "\",\"resultDate\":\"" + today + "\",\"result\":\"APPROVED\",\"content\":\"후기\"}").getStatus())
				.as("비자 후기").isEqualTo(201);
	}

	// 완료 조건 3: 쪽지(문의 시작)도 예전 메일 인증 회원이 보낼 수 있다
	@Test
	void 예전_메일_인증_회원은_중고글_쪽지도_시작할_수_있다() throws Exception {
		User seller = TestLogin.인증된_사용자(userRepository, jdbc, "seller1", "연세대학교");
		User buyer = TestLogin.인증된_사용자(userRepository, jdbc, "buyer1", "연세대학교");
		MockHttpServletResponse item = 보내기("/market-items", 쿠키(seller),
				"{\"title\":\"팝니다\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}");
		assertThat(item.getStatus()).isEqualTo(201);
		long itemId = json(item).get("id").asLong();

		int s = 보내기("/market-items/" + itemId + "/conversations", 쿠키(buyer), "{\"content\":\"안녕하세요\"}").getStatus();

		assertThat(s).isIn(200, 201);
	}

	// 완료 조건 4: 허가서 자동 승인(가짜 판독기) 뒤에도 글쓰기 201
	@Test
	void 허가서_자동_승인_뒤에는_글쓰기_201이다() throws Exception {
		판독기.reset();
		판독기.text = String.join("\n", "표준입학허가서", "국적(Country of citizenship): 베트남", "학교명(School name): 연세대학교",
				"표준입학허가서 번호(Certificate of Admission No.): 제 2013-0777 호");
		User u = 회원("autoapprove");
		assertThat(보내기("/posts", 쿠키(u),
				"{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 연세id + ",\"category\":\"CHAT\"}").getStatus()).isEqualTo(403);

		MockHttpServletResponse r = mockMvc.perform(multipart(ME_URL)
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림())).param("consent", "true").cookie(쿠키(u))
				.with(csrf())).andReturn().getResponse();

		assertThat(json(r).get("status").asString()).isEqualTo("APPROVED");
		assertThat(보내기("/posts", 쿠키(u),
				"{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 연세id + ",\"category\":\"CHAT\"}").getStatus()).isEqualTo(201);
		assertThat(인증칸(u).get("school_email_hash")).isNull(); // 허가서 인증은 메일 해시 칸을 건드리지 않는다
		판독기.reset();
	}

	// 완료 조건 5: 철회 뒤 글쓰기 403
	@Test
	void 메일_해시_회원이_철회하면_이후_글쓰기는_403이다() throws Exception {
		User u = TestLogin.인증된_사용자(userRepository, jdbc, "withdrawwrite", "연세대학교");
		assertThat(보내기("/posts", 쿠키(u),
				"{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 연세id + ",\"category\":\"CHAT\"}").getStatus()).isEqualTo(201);

		assertThat(mockMvc.perform(delete(ME_URL).with(csrf()).cookie(쿠키(u))).andReturn().getResponse().getStatus())
				.isEqualTo(204);

		assertThat(보내기("/posts", 쿠키(u),
				"{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 연세id + ",\"category\":\"CHAT\"}").getStatus()).isEqualTo(403);
	}

	// 완료 조건 9: school_email_hash가 있는 회원이 탈퇴해도 204, 다른 해시 회원은 그대로
	@Test
	void 메일_해시가_있는_회원도_탈퇴가_되고_다른_회원은_그대로다() throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"leaver\",\"nickname\":\"떠나는이\",\"password\":\"password123\"}")).andReturn();
		TestLogin.학교_인증_처리(jdbc, "leaver", "연세대학교");
		User other = TestLogin.인증된_사용자(userRepository, jdbc, "stayer", "연세대학교");
		Object otherHash = 인증칸(other).get("school_email_hash");
		assertThat(otherHash).isNotNull();
		MockHttpServletResponse login = mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"leaver\",\"password\":\"password123\"}")).andReturn().getResponse();
		Cookie access = new Cookie("access_token", login.getCookie("access_token").getValue());

		int s = mockMvc.perform(delete("/users/me").with(csrf()).cookie(access).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"password123\"}")).andReturn().getResponse().getStatus();

		assertThat(s).isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from users where username = 'leaver'", Long.class)).isZero();
		assertThat(인증칸(other).get("school_email_hash")).isEqualTo(otherHash);
		assertThat(mockMvc.perform(get(ME_URL).cookie(쿠키(other))).andReturn().getResponse().getStatus()).isEqualTo(200);
	}

	// 완료 조건 7: 메일 인증을 걷어낼 때(S20-f) 새 V 파일을 만들지 않았다.
	// 원래는 "V14가 마지막"으로 확인했지만 V 파일은 다른 기능(S21-a의 V15 등)으로 계속 늘어난다.
	// 그래서 의도(메일 제거를 위한 V 파일이 없다)만 남기고, V14 이후 파일 이름에 메일 관련 말이 없는지를 본다.
	@Test
	void 메일_인증_제거를_위한_새_V_파일이_없다() throws Exception {
		Integer max = jdbc.queryForObject("select max(version::int) from flyway_schema_history where version is not null",
				Integer.class);
		assertThat(max).isGreaterThanOrEqualTo(14);
		try (var files = Files.list(Path.of("src/main/resources/db/migration"))) {
			assertThat(files.map(p -> p.getFileName().toString().toLowerCase())
					.filter(n -> n.matches("v(1[4-9]|[2-9]\\d)__.*") && n.contains("mail"))).isEmpty();
		}
		// 남기기로 한 표·칸은 그대로
		assertThat(jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'school_email_domains'",
				Long.class)).isEqualTo(1L);
	}

	// 완료 조건 6·8: 설정·문서에 메일 흔적이 없다(대소문자 구분: school_email_hash 같은 정상 이름은 제외)
	@Test
	void 설정과_문서에_메일_인증_흔적이_없다() throws Exception {
		String gradle = Files.readString(Path.of("build.gradle"));
		assertThat(gradle).doesNotContain("starter-mail");
		for (String f : new String[] { "src/main/resources/application.properties", ".env.example", "docker-compose.yml" }) {
			String t = Files.readString(Path.of(f));
			assertThat(t).as(f).doesNotContain("MAIL_").doesNotContain("spring.mail").doesNotContain("app.mail")
					.doesNotContain("app.verification").doesNotContain("email-verify");
		}
		String api = Files.readString(Path.of("docs/api.md"));
		assertThat(api).doesNotContain("email-verify");
		// 15·16번 제목은 "삭제됨" 한 번씩만 있고, 다른 API 제목에는 쓰이지 않는다
		assertThat(api.lines().filter(l -> l.matches("###\\s+(15|16)\\.\\s.*")).toList()).hasSize(2)
				.allMatch(l -> l.contains("삭제됨"));
	}
}
