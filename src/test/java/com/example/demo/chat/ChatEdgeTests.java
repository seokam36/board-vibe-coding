package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.LinkedHashMap;
import java.util.Map;

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

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S17-c 빈틈 검사(S17-e4: AI가 양식에 채운 keyword로 찾도록 고침): 검색 경계(대소문자·LIKE 특수문자·1글자·다른 학교), 글자 수 경계(이모지), 삭제된 글·탈퇴 판매자.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatEdgeTests {

	static final String NO_RESULT = "관련 글을 찾지 못했어요. 다른 단어로 물어봐 주세요.";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired ScriptedLlmClient llm; // 대본 읽는 가짜 AI

	User 판매자;
	Cookie 판매자쿠키;
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "edgeseller", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		쿠키 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "edgeasker"));
		llm.reset();
		양식("자전거"); // 기본: AI가 keyword "자전거"를 채운다
		llm.chatReturns("답");
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private long 올리기(Cookie 판매자, String title, String content) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		body.put("content", content);
		body.put("tradeType", "SALE");
		body.put("price", 1000);
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(body)).cookie(판매자).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	// 1번째 호출(양식)의 대본
	private void 양식(String keyword) {
		llm.jsonReturns(om.writeValueAsString(Map.of("needSearch", true, "keyword", keyword)));
	}

	// AI가 keyword를 이렇게 채웠을 때의 검색 결과(질문 글자는 검색에 안 쓰인다: 단어가 정상이면 정문으로 간다)
	private MockHttpServletResponse 찾기(String keyword) throws Exception {
		양식(keyword);
		return 질문("검색 질문");
	}

	private MockHttpServletResponse 질문(String q) throws Exception {
		return mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", q))).cookie(쿠키).with(csrf())).andReturn().getResponse();
	}

	private int 출처수(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString()).get("sources").size();
	}

	@Test
	void 검색_영어_글_Bicycle을_소문자_bicycle로_물어도_찾는다() throws Exception {
		올리기(판매자쿠키, "Bicycle for sale", "good");
		assertThat(출처수(찾기("bicycle"))).isEqualTo(1);
		assertThat(출처수(찾기("BICYCLE"))).isEqualTo(1);
	}

	@Test
	void 검색_키워드의_퍼센트와_밑줄은_와일드카드가_아니라_글자로만_찾는다() throws Exception {
		올리기(판매자쿠키, "자전거 팝니다", "내용");
		올리기(판매자쿠키, "할인 100% 책상", "내용");
		올리기(판매자쿠키, "a_b 케이블", "내용");

		MockHttpServletResponse pct = 찾기("100%");
		assertThat(pct.getStatus()).isEqualTo(200);
		assertThat(출처수(pct)).isEqualTo(1); // "100%" 글만, 전체가 걸리면 3
		assertThat(출처수(찾기("%%"))).isZero(); // "%%"가 와일드카드면 전부 걸린다
		assertThat(출처수(찾기("__"))).isZero(); // "_"가 와일드카드면 2글자 이상 글이 전부 걸린다
		assertThat(출처수(찾기("a_b"))).isEqualTo(1);
		assertThat(출처수(찾기("\\\\"))).isZero(); // 역슬래시 두 개도 오류 없이
	}

	@Test
	void 검색_한_글자_키워드와_한_글자_질문_단어뿐이면_0건이고_답_호출은_안_한다() throws Exception {
		올리기(판매자쿠키, "책 팝니다", "책 많음");
		양식("책"); // 한 글자 키워드는 못 쓰니 비상구로 질문 단어를 찾는데, "책 줘"도 쓸 단어(2글자 이상)가 없다
		MockHttpServletResponse r = 질문("책 줘");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(om.readTree(r.getContentAsString()).get("answer").asString()).isEqualTo(NO_RESULT);
		assertThat(출처수(r)).isZero();
		assertThat(llm.chatCalls).isEmpty();
	}

	@Test
	void 검색_다른_학교_글도_나온다() throws Exception {
		User 고대판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "edgekorea", "고려대학교");
		올리기(TestLogin.쿠키(tokenService, 고대판매자), "자전거 고대", "내용");
		MockHttpServletResponse r = 질문("자전거");
		assertThat(출처수(r)).isEqualTo(1);
		assertThat(llm.chatCalls).hasSize(1);
		assertThat(llm.chatCalls.get(0).user()).contains("고려대학교");
	}

	@Test
	void 길이_이모지_500개는_통과하고_501개는_400이다() throws Exception {
		assertThat(질문("😀".repeat(500)).getStatus()).isEqualTo(200); // char로는 1000칸이지만 코드포인트 500
		assertThat(질문("😀".repeat(501)).getStatus()).isEqualTo(400);
		assertThat(질문("가".repeat(500)).getStatus()).isEqualTo(200);
		assertThat(질문("가".repeat(501)).getStatus()).isEqualTo(400);
	}

	@Test
	void 길이_전각_공백만_있는_질문은_400이다() throws Exception {
		assertThat(질문("\u3000\u3000\u3000").getStatus()).isEqualTo(400);
		assertThat(llm.jsonCalls).isEmpty();
		assertThat(llm.chatCalls).isEmpty();
	}

	@Test
	void 본문_이모지가_경계에_걸려도_반쪽_글자_없이_300개로_자른다() throws Exception {
		올리기(판매자쿠키, "자전거 팝니다", "가".repeat(299) + "😀".repeat(50));
		질문("자전거");
		assertThat(llm.chatCalls).hasSize(1);
		String msg = llm.chatCalls.get(0).user();
		assertThat(msg).contains("가".repeat(299) + "😀\n"); // 299 + 이모지 1개 = 300, 그 뒤는 잘림
		assertThat(msg).doesNotContain("😀😀");
		// 짝 없는 서로게이트(깨진 글자)가 없다
		for (int i = 0; i < msg.length(); i++) {
			char c = msg.charAt(i);
			if (Character.isHighSurrogate(c)) {
				assertThat(Character.isLowSurrogate(msg.charAt(i + 1))).isTrue();
				i++;
			} else {
				assertThat(Character.isLowSurrogate(c)).isFalse();
			}
		}
	}

	@Test
	void 삭제된_글은_안_나오고_500도_아니다() throws Exception {
		long id = 올리기(판매자쿠키, "자전거 삭제될 글", "내용");
		jdbc.update("delete from market_items where id = ?", id);
		MockHttpServletResponse r = 질문("자전거");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(출처수(r)).isZero();
	}

	@Test
	void 탈퇴한_판매자의_글은_안_나오고_500도_아니다() throws Exception {
		User 탈퇴자 = TestLogin.인증된_사용자(userRepository, jdbc, "edgeleaver", "연세대학교");
		올리기(TestLogin.쿠키(tokenService, 탈퇴자), "자전거 탈퇴자 글", "내용");
		jdbc.update("delete from users where id = ?", 탈퇴자.getId()); // 글은 ON DELETE CASCADE로 같이 사라진다
		MockHttpServletResponse r = 질문("자전거");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(출처수(r)).isZero();
		JsonNode b = om.readTree(r.getContentAsString());
		assertThat(b.get("answer").asString()).isEqualTo(NO_RESULT);
	}
}
