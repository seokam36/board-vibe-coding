package com.example.demo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.TradeType;
import com.example.demo.market.repository.MarketItemRepository;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.domain.School;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;

// S17-e3: ChatSearchService.searchByCondition(AI가 채운 양식으로 찾기)을 진짜 DB(Testcontainers)에서 검사
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ChatSearchByConditionTests {

	@Autowired ChatSearchService service;
	@Autowired MarketItemRepository itemRepository;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired EntityManagerFactory emf;

	User 판매자;
	School 학교;
	long 시계 = 0; // 글마다 createdAt을 1초씩 늘려 "최신"을 분명히 한다

	@BeforeEach
	void 준비() {
		정리();
		판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "cbuser", "연세대학교");
		학교 = TestLogin.학교(schoolRepository, "연세대학교");
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// 글 하나 저장. 나중에 만든 글이 더 최신이 되도록 createdAt을 직접 정한다.
	private long 글(String title, String content, TradeType type, int price) {
		MarketItem saved = itemRepository.save(new MarketItem(판매자, 학교, title, content, type, price));
		jdbc.update("update market_items set created_at = ? where id = ?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(++시계)),
				saved.getId());
		return saved.getId();
	}

	private long 판매(String title, String content, int price) {
		return 글(title, content, TradeType.SALE, price);
	}

	private long 나눔(String title, String content) {
		return 글(title, content, TradeType.FREE, 0);
	}

	private List<Long> ids(List<ChatMarketItem> items) {
		return items.stream().map(ChatMarketItem::id).toList();
	}

	@Test
	void 제목이나_본문에_단어가_든_판매중_예약중_글만_나오고_거래완료는_안_나온다() {
		long 제목 = 판매("자전거 팝니다", "탈 만함", 10000);
		long 본문 = 판매("탈것", "중고 자전거예요", 10000);
		long 예약 = 판매("자전거 예약", "x", 10000);
		jdbc.update("update market_items set status = 'RESERVED' where id = ?", 예약);
		long 완료 = 판매("자전거 거래끝", "x", 10000);
		jdbc.update("update market_items set status = 'DONE' where id = ?", 완료);
		판매("책상", "무관", 10000);

		assertThat(ids(service.searchByCondition("자전거", null, false))).containsExactlyInAnyOrder(제목, 본문, 예약)
				.doesNotContain(완료);
	}

	@Test
	void 최대가격_이하만_나오고_나눔_0원도_포함되며_null이면_가격_조건이_없다() {
		long 삼만 = 판매("자전거 A", "x", 30000);
		long 삼만오천 = 판매("자전거 B", "x", 35000);
		long 무료 = 나눔("자전거 C", "x");

		assertThat(ids(service.searchByCondition("자전거", 30000, false))).containsExactlyInAnyOrder(삼만, 무료);
		assertThat(ids(service.searchByCondition("자전거", null, false))).containsExactlyInAnyOrder(삼만, 삼만오천, 무료);
	}

	@Test
	void freeOnly가_true면_나눔만_false면_둘_다() {
		long 판 = 판매("자전거 팝니다", "x", 5000);
		long 나 = 나눔("자전거 드려요", "x");

		assertThat(ids(service.searchByCondition("자전거", null, true))).containsExactly(나);
		assertThat(ids(service.searchByCondition("자전거", null, false))).containsExactlyInAnyOrder(판, 나);
	}

	@Test
	void 맞은_단어_수가_많은_글이_더_최신인_한_단어_글보다_앞에_온다() {
		long 두단어 = 판매("자전거 헬멧 세트", "x", 1000); // 먼저 올린 글(더 오래됨)
		long 한단어 = 판매("자전거만", "x", 1000); // 더 최신이지만 한 단어만

		assertThat(ids(service.searchByCondition("자전거 헬멧", null, false))).containsExactly(두단어, 한단어);
	}

	@Test
	void 한_단어가_제목과_본문_양쪽에_있어도_1로_센다() {
		long 두단어 = 판매("자전거 헬멧", "x", 1000);
		long 양쪽한단어 = 판매("자전거", "자전거 자전거 상태 좋음", 1000); // 더 최신, 단어 1개만 맞음

		assertThat(ids(service.searchByCondition("자전거 헬멧", null, false))).containsExactly(두단어, 양쪽한단어);
	}

	@Test
	void 점수가_같으면_최신순이고_createdAt이_같으면_id_큰_쪽이_앞이다() {
		long 옛 = 판매("자전거 옛", "x", 1000);
		long 새 = 판매("자전거 새", "x", 1000);
		assertThat(ids(service.searchByCondition("자전거", null, false))).containsExactly(새, 옛);

		long 같은시각1 = 판매("자전거 동시1", "x", 1000);
		long 같은시각2 = 판매("자전거 동시2", "x", 1000);
		jdbc.update("update market_items set created_at = '2030-01-01 00:00:00' where id in (?, ?)", 같은시각1, 같은시각2);
		assertThat(ids(service.searchByCondition("자전거", null, false))).containsExactly(같은시각2, 같은시각1, 새, 옛);
	}

	@Test
	void 맞는_글이_6개_넘어도_5건이고_밀려나는_건_점수가_낮은_글이다() {
		long 두단어 = 판매("자전거 헬멧", "x", 1000); // 가장 오래됐지만 점수 2
		long[] 한단어 = new long[5];
		for (int i = 0; i < 5; i++) {
			한단어[i] = 판매("자전거 " + i, "x", 1000); // 더 최신, 점수 1
		}
		// 점수 1짜리가 최신순으로 5개 -> 정렬이 LIMIT 앞이 아니면 두단어 글이 잘려 나간다
		List<Long> got = ids(service.searchByCondition("자전거 헬멧", null, false));
		assertThat(got).hasSize(5);
		assertThat(got.get(0)).isEqualTo(두단어);
		assertThat(got).doesNotContain(한단어[0]); // 6번째로 밀린 건 점수 1 중 가장 오래된 글
	}

	@Test
	void 퍼센트와_밑줄은_와일드카드가_아니라_글자로_찾는다() {
		long 퍼센트 = 판매("할인 100% 세일", "x", 1000);
		판매("1000원 균일", "x", 1000);
		long 밑줄 = 판매("a_b 모델", "x", 1000);
		판매("axb 모델", "x", 1000);

		assertThat(ids(service.searchByCondition("100%", null, false))).containsExactly(퍼센트);
		assertThat(ids(service.searchByCondition("a_b", null, false))).containsExactly(밑줄);
	}

	@Test
	void 영어_대소문자를_무시한다() {
		long 글 = 판매("rice cooker 팝니다", "x", 1000);
		assertThat(ids(service.searchByCondition("Rice Cooker", null, false))).containsExactly(글);
	}

	@Test
	void 단어가_없으면_DB를_안_부르고_빈_목록() {
		판매("a 글", "a", 1000);
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		stats.clear();
		try {
			assertThat(service.searchByCondition("a", null, false)).isEmpty();
			assertThat(service.searchByCondition("   ", null, false)).isEmpty();
			assertThat(service.searchByCondition(null, null, false)).isEmpty();
			assertThat(stats.getPrepareStatementCount()).isZero();
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}
}
