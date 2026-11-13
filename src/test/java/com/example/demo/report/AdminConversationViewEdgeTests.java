package com.example.demo.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
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

// S14-f2 빈틈 보강: 관리자 대화 읽기 API의 가장자리 경우(여러 신고·여러 방·양쪽 탈퇴·관리자 본인 방·필드 누출·특수 문자·잘못된 id)를 검사한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AdminConversationViewEdgeTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper om;

	User 관리자, 판매자, 구매자;
	Cookie 관리자쿠키, 판매자쿠키, 구매자쿠키;
	long 방id;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		관리자 = TestLogin.관리자(userRepository, jdbc, "acvedmin");
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "acvedseller", "연세대학교");
		구매자 = TestLogin.인증된_사용자(userRepository, jdbc, "acvedbuyer", "연세대학교");
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		구매자쿠키 = TestLogin.쿠키(tokenService, 구매자);
		방id = ConversationReportFixture.글과_방_만들기(mockMvc, om, 판매자쿠키, 구매자쿠키, "엣지 제목")[1];
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from conversations");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// 공용 도우미(ConversationReportFixture)로 넘기는 얇은 껍데기. 호출부를 짧게 유지한다.
	private MockHttpServletResponse 보기(Cookie cookie, Object reportId) throws Exception {
		return ConversationReportFixture.보기(mockMvc, cookie, reportId);
	}

	private JsonNode json(MockHttpServletResponse res) throws Exception {
		return ConversationReportFixture.json(om, res);
	}

	private long 신고하기(Cookie 쿠키, long 방) throws Exception {
		return ConversationReportFixture.대화_신고(mockMvc, om, 쿠키, 방);
	}

	private void 답장(Cookie 쿠키, long 방, String jsonContent) throws Exception {
		ConversationReportFixture.답장(mockMvc, 쿠키, 방, jsonContent);
	}

	private void 탈퇴(Cookie 쿠키) throws Exception {
		ConversationReportFixture.탈퇴(mockMvc, 쿠키);
	}

	@Test
	void 같은_방을_구매자와_판매자가_각각_신고하면_두_신고_id_모두_같은_방을_보여_준다() throws Exception {
		답장(판매자쿠키, 방id, "\"판매자 답장\"");
		long 구매자신고 = 신고하기(구매자쿠키, 방id);
		long 판매자신고 = 신고하기(판매자쿠키, 방id);

		JsonNode a = json(보기(관리자쿠키, 구매자신고));
		JsonNode b = json(보기(관리자쿠키, 판매자신고));

		assertThat(구매자신고).isNotEqualTo(판매자신고);
		assertThat(a.get("reportId").asLong()).isEqualTo(구매자신고);
		assertThat(b.get("reportId").asLong()).isEqualTo(판매자신고);
		assertThat(a.get("conversationId").asLong()).isEqualTo(방id);
		assertThat(b.get("conversationId").asLong()).isEqualTo(방id);
		assertThat(a.get("messages")).isEqualTo(b.get("messages"));
		assertThat(a.get("messages").size()).isEqualTo(2);
	}

	@Test
	void 방이_여러_개여도_다른_방의_쪽지가_섞이지_않는다() throws Exception {
		User 판매자2 = TestLogin.인증된_사용자(userRepository, jdbc, "acvedseller2", "연세대학교");
		User 구매자2 = TestLogin.인증된_사용자(userRepository, jdbc, "acvedbuyer2", "연세대학교");
		long 방2 = ConversationReportFixture.글과_방_만들기(mockMvc, om, TestLogin.쿠키(tokenService, 판매자2), TestLogin.쿠키(tokenService, 구매자2), "다른 방 제목")[1];
		답장(TestLogin.쿠키(tokenService, 판매자2), 방2, "\"다른 방 답장\"");
		답장(판매자쿠키, 방id, "\"이 방 답장\"");
		long 신고1 = 신고하기(구매자쿠키, 방id);
		long 신고2 = 신고하기(TestLogin.쿠키(tokenService, 구매자2), 방2);

		JsonNode r1 = json(보기(관리자쿠키, 신고1));
		JsonNode r2 = json(보기(관리자쿠키, 신고2));

		assertThat(r1.get("conversationId").asLong()).isEqualTo(방id);
		assertThat(r1.get("itemTitle").asString()).isEqualTo("엣지 제목");
		assertThat(r1.get("messages").size()).isEqualTo(2);
		assertThat(r1.toString()).doesNotContain("다른 방");
		assertThat(r2.get("conversationId").asLong()).isEqualTo(방2);
		assertThat(r2.get("messages").size()).isEqualTo(2);
		assertThat(r2.toString()).doesNotContain("이 방 답장");
	}

	@Test
	void 구매자와_판매자가_모두_탈퇴한_방도_200이고_모든_사람_칸이_null이다() throws Exception {
		답장(판매자쿠키, 방id, "\"판매자 답장\"");
		long 신고 = 신고하기(구매자쿠키, 방id);
		탈퇴(판매자쿠키);
		탈퇴(구매자쿠키);

		MockHttpServletResponse res = 보기(관리자쿠키, 신고);

		// 신고자(구매자)가 탈퇴해도 신고(reports 행)는 남으므로 관리자는 계속 볼 수 있다(200)
		assertThat(res.getStatus()).isEqualTo(200);
		JsonNode b = json(res);
		assertThat(b.get("buyer").isNull()).isTrue();
		assertThat(b.get("seller").isNull()).isTrue();
		assertThat(b.get("messages").size()).isEqualTo(2);
		assertThat(b.get("messages").get(0).get("sender").isNull()).isTrue();
		assertThat(b.get("messages").get(1).get("sender").isNull()).isTrue();
		assertThat(b.get("messages").get(1).get("content").asString()).isEqualTo("판매자 답장");
	}

	@Test
	void 관리자가_참가자인_방의_신고도_관리자_통로로_볼_수_있다() throws Exception {
		TestLogin.학교_인증_처리(jdbc, "acvedmin", "연세대학교");
		// 관리자가 구매자가 되어 새 글에 첫 쪽지를 보내 방을 만든다(Fixture는 구매자 첫 쪽지가 "첫째"로 고정이라 내용만 아래에서 확인)
		long 관리자방 = ConversationReportFixture.글과_방_만들기(mockMvc, om, 판매자쿠키, 관리자쿠키, "관리자가 산 글")[1];
		long 신고 = 신고하기(판매자쿠키, 관리자방);

		MockHttpServletResponse res = 보기(관리자쿠키, 신고);

		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(json(res).get("buyer").get("id").asLong()).isEqualTo(관리자.getId());
		assertThat(json(res).get("messages").get(0).get("content").asString()).isEqualTo("첫째");
	}

	@Test
	void 응답에_이메일_아이디_학교_같은_개인정보_필드가_없고_사람은_id와_nickname뿐이다() throws Exception {
		답장(판매자쿠키, 방id, "\"답장\"");
		long 신고 = 신고하기(구매자쿠키, 방id);

		JsonNode b = json(보기(관리자쿠키, 신고));

		assertThat(b.get("buyer").propertyNames()).containsExactlyInAnyOrder("id", "nickname");
		assertThat(b.get("seller").propertyNames()).containsExactlyInAnyOrder("id", "nickname");
		for (JsonNode m : b.get("messages")) {
			assertThat(m.get("sender").propertyNames()).containsExactlyInAnyOrder("id", "nickname");
		}
		String raw = b.toString().toLowerCase();
		assertThat(raw).doesNotContain("password", "email", "school", "role", "username", "unused");
	}

	@Test
	void 천자_쪽지와_줄바꿈_유니코드가_그대로_나온다() throws Exception {
		String 긴글 = "가".repeat(1000);
		String 특수 = "첫줄\n둘째줄\t탭 😀 \"따옴표\" <b>태그</b> \\역슬래시";
		답장(판매자쿠키, 방id, om.writeValueAsString(긴글));
		답장(구매자쿠키, 방id, om.writeValueAsString(특수));
		long 신고 = 신고하기(구매자쿠키, 방id);

		JsonNode m = json(보기(관리자쿠키, 신고)).get("messages");

		assertThat(m.size()).isEqualTo(3);
		assertThat(m.get(1).get("content").asString()).hasSize(1000).isEqualTo(긴글);
		assertThat(m.get(2).get("content").asString()).isEqualTo(특수);
	}

	@Test
	void 신고_id가_0_음수_Long_범위_초과면_각각_404_404_400이다() throws Exception {
		assertThat(보기(관리자쿠키, 0).getStatus()).as("0").isEqualTo(404);
		assertThat(보기(관리자쿠키, -1).getStatus()).as("음수").isEqualTo(404);
		assertThat(보기(관리자쿠키, "99999999999999999999").getStatus()).as("Long 초과").isEqualTo(400);
		assertThat(보기(관리자쿠키, "1.5").getStatus()).as("소수").isEqualTo(400);
	}

	@Test
	void 일반_회원이_없는_신고나_잘못된_id를_불러도_403이라_존재_여부가_드러나지_않는다() throws Exception {
		assertThat(보기(구매자쿠키, 999999999L).getStatus()).isEqualTo(403);
		assertThat(보기(구매자쿠키, "abc").getStatus()).isEqualTo(403);
	}
}
