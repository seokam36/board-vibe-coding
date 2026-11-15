package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.chat.dto.ChatResponse;
import com.example.demo.chat.dto.ChatSource;
import com.example.demo.chat.service.ChatService;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

// 진짜 Ollama로 ChatService를 한 번 불러 보는 시험. OLLAMA_LIVE_TEST=true일 때만 돌고, 아니면 건너뜀(skipped)이다.
// 가짜 글 몇 개를 DB에 넣고 질문해 실제 답과 출처를 출력한다(Ollama가 localhost:11434에서 qwen3:8b로 떠 있어야 함).
@EnabledIfEnvironmentVariable(named = "OLLAMA_LIVE_TEST", matches = "true")
@SpringBootTest(properties = "app.llm.mode=ollama")
@Import(TestcontainersConfiguration.class)
class ChatLiveTests {

	@Autowired ChatService chatService;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired MarketItemRepository marketItemRepository;
	@Autowired JdbcTemplate jdbc;

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// S17-e4: 진짜 AI가 1번째 호출에서 양식(keyword 등)을 채우고, 서버가 검색한 뒤, 2번째 호출에서 답한다.
	// AI의 답·양식은 실행마다 흔들릴 수 있어서 글(sources)이 맞는지를 주로 본다.
	@Test
	void 진짜_Ollama가_글을_근거로_자전거_질문에_답한다() {
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "liveseller", "연세대학교");
		var 학교 = TestLogin.학교(schoolRepository, "연세대학교");
		MarketItem 판매글 = marketItemRepository.save(new MarketItem(판매자, 학교, "중고 자전거 팝니다", "삼천리 자전거, 1년 사용했어요. 직거래만 해요.", TradeType.SALE, 30000));
		MarketItem 나눔글 = marketItemRepository.save(new MarketItem(판매자, 학교, "자전거 헬멧 나눔", "사이즈 M, 가져가실 분 쪽지 주세요.", TradeType.FREE, 0));
		marketItemRepository.save(new MarketItem(판매자, 학교, "노트북 팝니다", "성능 좋아요.", TradeType.SALE, 400000));

		ChatResponse r = chatService.ask("자전거 있어?");

		System.out.println("LIVE 답: " + r.answer());
		System.out.println("LIVE 출처: " + r.sources());
		assertThat(r.answer()).isNotBlank();
		assertThat(r.sources()).extracting(ChatSource::id).containsExactlyInAnyOrder(판매글.getId(), 나눔글.getId());
	}

	@Test
	void 진짜_Ollama가_3만원_이하_질문의_가격_조건을_양식에_채워_25000원_글만_sources에_있다() {
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "liveprice", "연세대학교");
		var 학교 = TestLogin.학교(schoolRepository, "연세대학교");
		marketItemRepository.save(new MarketItem(판매자, 학교, "자전거 팝니다", "삼천리 자전거예요.", TradeType.SALE, 35000)); // 3만 원 초과: 걸리면 안 됨
		// 3만 원 이하 자전거 글이 있어야 "AI가 검색 안 함(needSearch=false)"으로 헛통과하지 않는다(그러면 sources가 비어 아래에서 실패)
		MarketItem 싼글 = marketItemRepository.save(new MarketItem(판매자, 학교, "자전거 싸게 팝니다", "접이식 자전거예요.", TradeType.SALE, 25000));
		marketItemRepository.save(new MarketItem(판매자, 학교, "노트북 팝니다", "성능 좋아요.", TradeType.SALE, 400000));

		ChatResponse r = chatService.ask("3만 원 이하 자전거 있어?");

		System.out.println("LIVE 답: " + r.answer());
		System.out.println("LIVE 출처: " + r.sources());
		assertThat(r.answer()).isNotBlank();
		assertThat(r.sources()).extracting(ChatSource::id).containsExactly(싼글.getId());
	}

	// S17-e4 결정: 나눔 조건 칸이 없으니 "무료 나눔" 질문에도 판매 글이 함께 나온다. 답 문장은 흔들려서 sources만 본다.
	@Test
	void 진짜_Ollama가_무료_나눔_전공책_질문에도_판매와_나눔_전공책이_둘_다_sources에_있다() {
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "livefree", "연세대학교");
		var 학교 = TestLogin.학교(schoolRepository, "연세대학교");
		MarketItem 판매글 = marketItemRepository.save(new MarketItem(판매자, 학교, "전공책 팝니다", "경영학 전공책이에요.", TradeType.SALE, 15000));
		MarketItem 나눔글 = marketItemRepository.save(new MarketItem(판매자, 학교, "전공책 나눔", "공학 전공책 드려요.", TradeType.FREE, 0));
		marketItemRepository.save(new MarketItem(판매자, 학교, "노트북 팝니다", "성능 좋아요.", TradeType.SALE, 400000));

		ChatResponse r = chatService.ask("무료 나눔 전공책 있어?");

		System.out.println("LIVE 답: " + r.answer());
		System.out.println("LIVE 출처: " + r.sources());
		assertThat(r.sources()).extracting(ChatSource::id).containsExactlyInAnyOrder(판매글.getId(), 나눔글.getId());
	}
}
