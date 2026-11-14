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
import com.example.demo.chat.service.ChatService;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

// 진짜 Ollama로 ChatService를 한 번 불러 보는 시험. OLLAMA_LIVE_TEST=true일 때만 돌고, 아니면 건너뜀(skipped)이다.
// 가짜 글 몇 개를 DB에 넣고 "자전거 있어?"를 물어 실제 답을 출력한다(Ollama가 localhost:11434에서 qwen3:8b로 떠 있어야 함).
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

	@Test
	void 진짜_Ollama가_글을_근거로_자전거_질문에_답한다() {
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "liveseller", "연세대학교");
		var 학교 = TestLogin.학교(schoolRepository, "연세대학교");
		marketItemRepository.save(new MarketItem(판매자, 학교, "중고 자전거 팝니다", "삼천리 자전거, 1년 사용했어요. 직거래만 해요.", TradeType.SALE, 30000));
		marketItemRepository.save(new MarketItem(판매자, 학교, "자전거 헬멧 나눔", "사이즈 M, 가져가실 분 쪽지 주세요.", TradeType.FREE, 0));
		marketItemRepository.save(new MarketItem(판매자, 학교, "노트북 팝니다", "자전거 타듯 가벼워요.", TradeType.SALE, 400000));

		ChatResponse r = chatService.ask("자전거 있어?");

		System.out.println("LIVE 답: " + r.answer());
		System.out.println("LIVE 출처: " + r.sources());
		assertThat(r.answer()).isNotBlank();
		assertThat(r.sources()).hasSize(3);
	}
}
