package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S17-c 재검사: 진짜 서버(실제 포트)로 확인한다. MockMvc가 못 보는 "예상 밖 예외 -> 실제 500"과
// sources.title == GET /market-items/{id}의 title, 예약중 나눔 글의 프롬프트를 본다. AI는 가짜.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
class ChatRealServerTests {

	@LocalServerPort int port;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired MarketItemRepository marketItemRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired ScriptedLlmClient llm; // 대본 읽는 가짜 AI

	final HttpClient client = HttpClient.newHttpClient();
	String 로그인쿠키;
	User 판매자;

	@BeforeEach
	void 준비() {
		정리();
		llm.reset();
		llm.jsonReturns("{\"needSearch\":true,\"keyword\":\"자전거\"}"); // 1번째 호출(양식): keyword 자전거
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "realseller", "연세대학교");
		User 질문자 = TestLogin.사용자(userRepository, "realasker");
		로그인쿠키 = "access_token=" + TestLogin.쿠키(tokenService, 질문자).getValue();
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private HttpResponse<String> 질문(String question) throws Exception {
		String base = "http://localhost:" + port;
		// CSRF 토큰을 먼저 받아 쿠키와 헤더로 같이 보낸다
		HttpResponse<String> c = client.send(HttpRequest.newBuilder(URI.create(base + "/csrf")).build(), BodyHandlers.ofString());
		String xsrf = c.headers().allValues("Set-Cookie").stream().filter(s -> s.startsWith("XSRF-TOKEN="))
				.map(s -> s.split(";")[0].substring("XSRF-TOKEN=".length())).findFirst().orElseThrow();
		return client.send(HttpRequest.newBuilder(URI.create(base + "/chat"))
				.header("Content-Type", "application/json")
				.header("Cookie", 로그인쿠키 + "; XSRF-TOKEN=" + xsrf).header("X-XSRF-TOKEN", xsrf)
				.POST(BodyPublishers.ofString(om.writeValueAsString(java.util.Map.of("question", question)))).build(),
				BodyHandlers.ofString());
	}

	private MarketItem 저장(String title, TradeType type, int price) {
		var 학교 = TestLogin.학교(schoolRepository, "연세대학교");
		return marketItemRepository.save(new MarketItem(판매자, 학교, title, "내용", type, price));
	}

	@Test
	void 조건7_AI가_예상_밖_예외를_던지면_실제_서버는_500을_돌려준다() throws Exception {
		저장("자전거 팝니다", TradeType.SALE, 1000);
		llm.chatThrows(new IllegalArgumentException("호출 쪽 버그"));

		HttpResponse<String> r = 질문("자전거");

		assertThat(r.statusCode()).isEqualTo(500);
		assertThat(r.body()).doesNotContain("호출 쪽 버그");
	}

	@Test
	void 추가_sources의_title은_글_상세_조회의_title과_같은_원본이다() throws Exception {
		MarketItem item = 저장("자전거 팝니다", TradeType.SALE, 1000);
		String 원본 = "자전거\n\n  팝니다";
		jdbc.update("update market_items set title = ? where id = ?", 원본, item.getId());
		llm.chatReturns("답");

		JsonNode source = om.readTree(질문("자전거").body()).get("sources").get(0);
		HttpResponse<String> detail = client.send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/market-items/" + item.getId())).header("Cookie", 로그인쿠키).build(),
				BodyHandlers.ofString());

		assertThat(detail.statusCode()).isEqualTo(200);
		assertThat(source.get("title").asString()).isEqualTo(원본)
				.isEqualTo(om.readTree(detail.body()).get("title").asString());
	}

	@Test
	void 추가_예약중_나눔_글은_프롬프트에_무료_나눔과_상태_예약중으로_들어간다() throws Exception {
		MarketItem item = 저장("자전거 헬멧 나눔", TradeType.FREE, 0);
		jdbc.update("update market_items set status = 'RESERVED' where id = ?", item.getId());
		llm.chatReturns("답");

		assertThat(질문("헬멧").statusCode()).isEqualTo(200);

		assertThat(llm.chatCalls).hasSize(1);
		assertThat(llm.chatCalls.get(0).user()).contains("#" + item.getId() + " | 자전거 헬멧 나눔 | ").contains("가격 무료(나눔) | 상태 예약중");
	}

	@Test
	void 추가_나눔_글은_DB에서_가격이_0이_아니면_저장되지_않는다() {
		var 학교 = TestLogin.학교(schoolRepository, "연세대학교");
		MarketItem bad = new MarketItem(판매자, 학교, "나눔인데 가격", "내용", TradeType.FREE, 500);
		org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> marketItemRepository.saveAndFlush(bad));
	}
}
