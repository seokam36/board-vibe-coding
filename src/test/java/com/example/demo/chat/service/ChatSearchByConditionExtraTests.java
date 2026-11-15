package com.example.demo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.chat.dto.ChatMarketItem;
import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;
import com.example.demo.market.repository.MarketItemChatSearch;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.domain.School;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

// S17-e3 보강 검사: 조건 조합, 0원, 예약중, DTO 내용, 단어 10개 상한 (옛 질문 단어 검색은 S17-e4에서 지워서 그 검사도 뺐다)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatSearchByConditionExtraTests {

	@Autowired ChatSearchService service;
	@Autowired MarketItemRepository itemRepository;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired JdbcTemplate jdbc;

	User 판매자;
	School 학교;
	long 시계 = 0;

	@BeforeEach
	void 준비() {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "cbextra", "연세대학교");
		학교 = TestLogin.학교(schoolRepository, "연세대학교");
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private long 글(String title, String content, TradeType type, int price) {
		MarketItem saved = itemRepository.save(new MarketItem(판매자, 학교, title, content, type, price));
		jdbc.update("update market_items set created_at = ? where id = ?",
				Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(++시계)), saved.getId());
		return saved.getId();
	}

	private List<Long> ids(List<ChatMarketItem> items) {
		return items.stream().map(ChatMarketItem::id).toList();
	}

	@Test
	void 최대가격_0원이면_나눔_0원_글만_나온다() {
		글("자전거 판매", "x", TradeType.SALE, 1000);
		long 나눔 = 글("자전거 나눔", "x", TradeType.FREE, 0);

		assertThat(ids(service.searchByCondition("자전거", 0))).containsExactly(나눔);
	}

	@Test
	void 예약중_글도_나오고_상태가_RESERVED로_담긴다() {
		long 예약 = 글("자전거 예약", "x", TradeType.SALE, 1000);
		jdbc.update("update market_items set status = 'RESERVED' where id = ?", 예약);

		List<ChatMarketItem> got = service.searchByCondition("자전거", null);
		assertThat(ids(got)).containsExactly(예약);
		assertThat(got.get(0).status()).isEqualTo(ItemStatus.RESERVED);
	}

	@Test
	void 결과에_학교_이름이_채워지고_본문은_300자로_잘린다() {
		글("자전거", "가".repeat(500), TradeType.SALE, 1000);

		ChatMarketItem item = service.searchByCondition("자전거", null).get(0);
		assertThat(item.schoolName()).isEqualTo("연세대학교");
		assertThat(item.snippet()).hasSize(300);
	}

	@Test
	void 단어가_null이거나_비면_IllegalArgumentException() {
		// 점수 식을 못 만들어 NPE(500)가 나던 자리: 조건을 만들 때 바로 호출 쪽 잘못으로 알린다
		assertThatThrownBy(() -> MarketItemChatSearch.openItemsByCondition(null, null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("keywords는 비어 있을 수 없습니다.");
		assertThatThrownBy(() -> MarketItemChatSearch.openItemsByCondition(List.of(), null))
				.isInstanceOf(IllegalArgumentException.class).hasMessage("keywords는 비어 있을 수 없습니다.");
	}

	@Test
	void 단어는_앞에서_10개까지만_쓰인다() {
		// 11번째 단어(단어11)만 든 글은 상한 때문에 안 나와야 한다
		long 첫단어 = 글("단어01 글", "x", TradeType.SALE, 1000);
		글("단어11 글", "x", TradeType.SALE, 1000);

		String q = "단어01 단어02 단어03 단어04 단어05 단어06 단어07 단어08 단어09 단어10 단어11";
		assertThat(ids(service.searchByCondition(q, null))).containsExactly(첫단어);
	}
}
