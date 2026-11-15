package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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

// S17-e4 완료 조건 검사: POST /chat의 2단계 흐름(AI 양식 -> 서버 검색 -> AI 답).
// 가짜 AI(ScriptedLlmClient)가 1번째 호출(chatJson)에 대본 JSON을, 2번째 호출(chat)에 "답"을 주고, 받은 인자를 기록한다.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatFlowTests {

	static final String NO_RESULT = "관련 글을 찾지 못했어요. 다른 단어로 물어봐 주세요.";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired ScriptedLlmClient llm;

	Cookie 판매자쿠키;
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		llm.reset();
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "flowseller", "연세대학교");
		판매자쿠키 = TestLogin.쿠키(tokenService, 판매자);
		쿠키 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "flowasker"));
		llm.chatReturns("답");
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private long 올리기(String title, String tradeType, Integer price) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		body.put("content", "내용");
		body.put("tradeType", tradeType);
		if (price != null) {
			body.put("price", price);
		}
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(body)).cookie(판매자쿠키).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기: " + title).isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private MockHttpServletResponse 질문(String question) throws Exception {
		return mockMvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(Map.of("question", question))).cookie(쿠키).with(csrf())).andReturn()
				.getResponse();
	}

	private List<Long> 출처ids(MockHttpServletResponse r) throws Exception {
		List<Long> ids = new ArrayList<>();
		for (JsonNode s : om.readTree(r.getContentAsString()).get("sources")) {
			ids.add(s.get("id").asLong());
		}
		return ids;
	}

	// ============ 정문: AI가 양식을 채움 ============

	@Test
	void 가격_조건이_있으면_그_가격_이하_글만_sources에_나오고_나눔도_포함된다() throws Exception {
		long 삼만 = 올리기("자전거 삼만", "SALE", 30000);
		long 삼만오천 = 올리기("자전거 삼만오천", "SALE", 35000);
		long 나눔 = 올리기("자전거 나눔", "FREE", null);
		llm.jsonReturns("{\"keyword\":\"자전거\",\"maxPrice\":30000}");

		MockHttpServletResponse r = 질문("3만 원 이하 자전거 있어?");

		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode body = om.readTree(r.getContentAsString());
		assertThat(body.get("answer").asString()).isEqualTo("답");
		assertThat(출처ids(r)).containsExactlyInAnyOrder(삼만, 나눔).doesNotContain(삼만오천);
		assertThat(llm.jsonCalls).hasSize(1);
		assertThat(llm.chatCalls).hasSize(1);
	}

	@Test
	void 답_호출이_받은_메시지에는_글_번호_제목_가격이_있고_닉네임은_없으며_시스템_지시문에_모두_검토_문장이_있다() throws Exception {
		long id = 올리기("자전거 팝니다", "SALE", 30000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");

		질문("자전거 있어?");

		ScriptedLlmClient.Call c = llm.chatCalls.get(0);
		assertThat(c.user()).contains("#" + id).contains("자전거 팝니다").contains("30000원").doesNotContain("flowseller");
		assertThat(c.system()).contains("질문에 해당하는 글을 모두 빠짐없이 검토해서 답해.");
	}

	@Test
	void 양식_호출은_스키마에_세_칸만_있고_지시문에_공백으로_구분이_있고_tradeType은_없고_질문을_그대로_받는다() throws Exception {
		올리기("자전거 팝니다", "SALE", 30000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");

		질문("  자전거 있어?  ");

		assertThat(llm.jsonCalls).hasSize(1);
		ScriptedLlmClient.Call c = llm.jsonCalls.get(0);
		// 나눔 칸(S17-e4)과 needSearch 칸(S17-e5)을 뺐다. 스키마에도 지시문에도 없어야 한다.
		assertThat(c.schema()).contains("keyword", "maxPrice").doesNotContain("tradeType", "needSearch");
		assertThat(c.system()).contains("공백으로 구분").contains("keyword를 비워").doesNotContain("tradeType", "needSearch");
		assertThat(c.user()).isEqualTo("자전거 있어?");
		// 스키마 글자가 올바른 JSON이고 필수 칸이 없다(둘 다 비워도 된다)
		assertThat(om.readTree(c.schema()).has("required")).isFalse();
		assertThat(om.readTree(c.schema()).get("properties").size()).isEqualTo(2);
	}

	// S17-e4 결정: 양식에서 tradeType 칸을 뺐다. AI가 FREE·SALE을 보내도 모르는 칸이라 무시되고 판매·나눔이 둘 다 나온다.
	@Test
	void AI가_tradeType을_보내도_무시되어_판매와_나눔이_둘_다_나온다() throws Exception {
		long 판매 = 올리기("자전거 판매", "SALE", 10000);
		long 나눔 = 올리기("자전거 나눔", "FREE", null);

		llm.jsonReturns("{\"keyword\":\"자전거\",\"tradeType\":\"FREE\"}");
		assertThat(출처ids(질문("자전거 나눔"))).containsExactlyInAnyOrder(판매, 나눔);

		llm.jsonReturns("{\"keyword\":\"자전거\",\"tradeType\":\"SALE\"}");
		assertThat(출처ids(질문("자전거 팝니다"))).containsExactlyInAnyOrder(판매, 나눔);
	}

	// AI가 "팔아?"·"싸게"에 maxPrice 0을 쓰는 버릇 때문에, 0 이하는 가격 조건 없음으로 본다(35,000원 판매 글도 나와야 한다).
	@Test
	void maxPrice가_0이거나_음수면_가격_조건_없이_35000원_판매_글도_나온다() throws Exception {
		long 삼만오천 = 올리기("자전거 삼만오천", "SALE", 35000);
		long 나눔 = 올리기("자전거 나눔", "FREE", null);

		for (int zeroOrNeg : new int[] {0, -1}) {
			llm.jsonReturns("{\"keyword\":\"자전거\",\"maxPrice\":" + zeroOrNeg + "}");
			assertThat(출처ids(질문("자전거 팔아?"))).as("maxPrice " + zeroOrNeg).containsExactlyInAnyOrder(삼만오천, 나눔);
		}
	}

	@Test
	void keyword가_없거나_비었거나_공백뿐이면_200_고정_문구_빈_sources이고_답_호출은_0번이다() throws Exception {
		올리기("안녕 자전거 팝니다", "SALE", 30000); // "안녕"을 질문 단어로 검색하는 비상구로 새면 이 글이 걸린다
		// {}, 빈 값, 공백뿐, needSearch만 true(keyword 없음), 제어 문자만(정리 뒤 단어 0개)
		for (String form : List.of("{}", "{\"keyword\":\"\"}", "{\"keyword\":\"   \"}", "{\"needSearch\":true}", "{\"keyword\":null}",
				"{\"keyword\":\"\\u0000\\u0001\"}", "{\"needSearch\":false}")) {
			llm.reset();
			llm.chatReturns("답");
			llm.jsonReturns(form);

			MockHttpServletResponse r = 질문("안녕");

			assertThat(r.getStatus()).as(form).isEqualTo(200);
			assertThat(om.readTree(r.getContentAsString()).get("answer").asString()).as(form).isEqualTo(NO_RESULT);
			assertThat(출처ids(r)).as(form).isEmpty();
			assertThat(llm.chatCalls).as(form).isEmpty();
		}
	}

	// S17-e5 결정: needSearch 칸은 뺐고, AI가 보내도 무시한다(false를 보내도 keyword가 있으면 검색한다).
	@Test
	void AI가_needSearch를_보내도_무시하고_keyword로_검색한다() throws Exception {
		long id = 올리기("자전거 팝니다", "SALE", 30000);

		llm.jsonReturns("{\"needSearch\":false,\"keyword\":\"자전거\"}");
		assertThat(출처ids(질문("자전거 있어?"))).containsExactly(id);

		llm.jsonReturns("{\"needSearch\":true,\"keyword\":\"자전거\"}");
		assertThat(출처ids(질문("자전거 있어?"))).containsExactly(id);
	}

	// S17-e5 결정: AI keyword는 한 글자도 쓴다("책", "옷"). 대신 "책"이 "책상"에도 걸린다(알려진 한계).
	@Test
	void AI_keyword가_한_글자_책이면_전공책과_책상이_나오고_자전거는_안_나온다() throws Exception {
		long 전공책 = 올리기("전공책 팝니다", "SALE", 10000);
		long 책상 = 올리기("책상 팝니다", "SALE", 20000);
		long 자전거 = 올리기("자전거 팝니다", "SALE", 30000);
		llm.jsonReturns("{\"keyword\":\"책\"}");

		MockHttpServletResponse r = 질문("책 있어?");

		assertThat(출처ids(r)).containsExactlyInAnyOrder(전공책, 책상).doesNotContain(자전거);
		assertThat(llm.chatCalls).hasSize(1);
	}

	@Test
	void AI_keyword가_한_글자_옷이면_겨울_옷_나눔이_나온다() throws Exception {
		long 옷 = 올리기("겨울 옷 나눔", "FREE", null);
		올리기("자전거 팝니다", "SALE", 30000);
		llm.jsonReturns("{\"keyword\":\"옷\"}");

		assertThat(출처ids(질문("옷 나눔해?"))).containsExactly(옷);
	}

	@Test
	void AI_keyword에_한_글자_단어가_섞여도_쓰고_중복은_한_번만_센다() throws Exception {
		long 전공책 = 올리기("전공책 팝니다", "SALE", 10000);
		long 옷 = 올리기("옷 나눔", "FREE", null);
		올리기("자전거 팝니다", "SALE", 30000);
		llm.jsonReturns("{\"keyword\":\"책, 옷 책\"}");

		assertThat(출처ids(질문("물어봄"))).containsExactlyInAnyOrder(전공책, 옷);
	}

	@Test
	void 검색_결과가_0건이면_200_고정_문구_빈_sources이고_답_호출은_0번이다() throws Exception {
		올리기("노트북 팝니다", "SALE", 500000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");

		MockHttpServletResponse r = 질문("자전거 있어?");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(om.readTree(r.getContentAsString()).get("answer").asString()).isEqualTo(NO_RESULT);
		assertThat(출처ids(r)).isEmpty();
		assertThat(llm.chatCalls).isEmpty();
	}

	@Test
	void 키워드의_쉼표_슬래시_중점은_공백으로_바뀌어_여러_단어로_검색된다() throws Exception {
		long 자전거 = 올리기("자전거 팝니다", "SALE", 1000);
		long 헬멧 = 올리기("헬멧 팝니다", "SALE", 1000);
		올리기("노트북 팝니다", "SALE", 1000);

		for (String keyword : List.of("자전거, 헬멧", "자전거/헬멧", "자전거·헬멧")) {
			llm.jsonReturns(om.writeValueAsString(Map.of("keyword", keyword)));
			assertThat(출처ids(질문("물어봄"))).as(keyword).containsExactlyInAnyOrder(자전거, 헬멧);
		}
	}

	@Test
	void 맞은_단어_수가_많은_글이_앞에_온다() throws Exception {
		long 두단어 = 올리기("자전거 헬멧 세트", "SALE", 1000);
		long 한단어_새글 = 올리기("자전거만", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거 헬멧\"}");

		assertThat(출처ids(질문("물어봄"))).containsExactly(두단어, 한단어_새글);
	}

	@Test
	void maxPrice가_문자열_숫자여도_읽어서_쓴다() throws Exception {
		long 싼글 = 올리기("자전거 싼글", "SALE", 30000);
		올리기("자전거 비싼글", "SALE", 35000);
		llm.jsonReturns("{\"keyword\":\"자전거\",\"maxPrice\":\"30000\"}");

		assertThat(출처ids(질문("물어봄"))).containsExactly(싼글);
	}

	@Test
	void 양식에_모르는_칸이_있어도_무시한다() throws Exception {
		long id = 올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\",\"foo\":123}");

		assertThat(출처ids(질문("물어봄"))).containsExactly(id);
	}

	// ============ 비상구 ============

	@Test
	void 양식이_JSON이_아니면_비상구로_질문_단어를_검색한다_500이_아니다() throws Exception {
		long 전공책 = 올리기("전공책 나눔", "FREE", null);
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("죄송해요");

		MockHttpServletResponse r = 질문("전공책 나눔 있어?");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(출처ids(r)).contains(전공책); // 질문 단어("전공책", "나눔")로 찾는다
		assertThat(llm.chatCalls).hasSize(1);
	}

	@Test
	void 양식이_깨진_JSON이거나_객체가_아니거나_JSON_null이거나_가격이_숫자가_아니면_비상구로_간다() throws Exception {
		long 전공책 = 올리기("전공책 팝니다", "SALE", 1000);

		// "{}"·{"keyword":"자전거"}는 정문(예전엔 needSearch가 없으면 비상구였다)이라 여기 없다
		for (String broken : List.of("{\"keyword\":\"자전거\",", "null", "[]", "\"문자열\"", "{\"maxPrice\":\"삼만\"}")) {
			llm.reset();
			llm.chatReturns("답");
			llm.jsonReturns(broken);
			MockHttpServletResponse r = 질문("전공책 있어?");
			assertThat(r.getStatus()).as(broken).isEqualTo(200);
			assertThat(출처ids(r)).as(broken).containsExactly(전공책);
		}
	}

	@Test
	void 키워드가_한_글자여도_가격_조건과_함께_그_글자로_검색한다() throws Exception {
		long 싼책 = 올리기("책 정리 싸게", "SALE", 20000);
		올리기("책 정리 비싸게", "SALE", 35000);
		llm.jsonReturns("{\"keyword\":\"책\",\"maxPrice\":30000}");

		MockHttpServletResponse r = 질문("정리 책 있어?");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(출처ids(r)).containsExactly(싼책); // 35,000원 글은 없다
	}

	// 비상구(질문 단어)는 예전 규칙 그대로 2글자 이상만 쓴다. "책 있어?"는 "있어?"만 남는다(동작 기록: 책 글은 안 걸리고, "있어?"가 든 글만 걸린다).
	@Test
	void 비상구의_질문_단어는_2글자_이상만_써서_책_있어는_있어_글자만_찾는다() throws Exception {
		long 책 = 올리기("책 팝니다", "SALE", 1000);
		long 있어글 = 올리기("자전거 있어?", "SALE", 1000);
		llm.jsonReturns("죄송해요"); // JSON 깨짐 -> 비상구①

		MockHttpServletResponse r = 질문("책 있어?");

		assertThat(출처ids(r)).containsExactly(있어글).doesNotContain(책); // "책"은 한 글자라 질문 단어로 안 쓴다
	}

	@Test
	void 비상구에서_질문이_한_글자_단어뿐이면_검색하지_않고_고정_문구다() throws Exception {
		올리기("책 팝니다", "SALE", 1000);
		llm.jsonReturns("죄송해요");

		MockHttpServletResponse r = 질문("책");

		assertThat(출처ids(r)).isEmpty();
		assertThat(om.readTree(r.getContentAsString()).get("answer").asString()).isEqualTo(NO_RESULT);
		assertThat(llm.chatCalls).isEmpty();
	}

	// keyword가 100자를 넘으면(비상구②) 질문 단어로 찾되 가격 조건은 유지한다. keyword가 비는 것(고정 문구)과 다르다.
	@Test
	void 키워드가_100자를_넘으면_비상구로_질문_단어를_검색하고_가격_조건은_유지한다() throws Exception {
		long 판매 = 올리기("전공책 판매", "SALE", 1000);
		long 나눔 = 올리기("전공책 나눔", "FREE", null);
		long 비싼책 = 올리기("전공책 비쌈", "SALE", 50000);
		String 긴키워드 = "가".repeat(101);

		llm.jsonReturns(om.writeValueAsString(Map.of("keyword", 긴키워드)));
		assertThat(출처ids(질문("전공책 있어?"))).containsExactlyInAnyOrder(판매, 나눔, 비싼책);

		llm.jsonReturns(om.writeValueAsString(Map.of("keyword", 긴키워드, "maxPrice", 10000)));
		assertThat(출처ids(질문("전공책 있어?"))).containsExactlyInAnyOrder(판매, 나눔).doesNotContain(비싼책);

		llm.jsonReturns(om.writeValueAsString(Map.of("keyword", 긴키워드, "maxPrice", 0))); // 0은 무시: 가격 조건 없음
		assertThat(출처ids(질문("전공책 있어?"))).containsExactlyInAnyOrder(판매, 나눔, 비싼책);
	}

	// ============ 예외 ============

	@Test
	void 양식_호출과_답_호출의_사용_불가와_시간_초과는_각각_503_504다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");

		llm.chatThrows(new com.example.demo.llm.service.LlmUnavailableException("x"));
		assertThat(질문("자전거").getStatus()).isEqualTo(503);
		llm.chatThrows(new com.example.demo.llm.service.LlmTimeoutException("x", new RuntimeException()));
		assertThat(질문("자전거").getStatus()).isEqualTo(504);

		llm.jsonThrows(new com.example.demo.llm.service.LlmUnavailableException("x"));
		assertThat(질문("자전거").getStatus()).isEqualTo(503);
		llm.jsonThrows(new com.example.demo.llm.service.LlmTimeoutException("x", new RuntimeException()));
		assertThat(질문("자전거").getStatus()).isEqualTo(504);
	}
}
