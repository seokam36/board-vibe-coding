package com.example.demo.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// 챗봇 검색의 순수 계산(단어 쪼개기·글자 자르기) 검사. 컨텍스트를 안 띄워서 빠르다. (package-private 메서드라 같은 패키지에 둔다)
class ChatSearchServiceTests {

	@Test
	void 이모지가_섞여도_코드포인트_300개로_자르고_글자가_반으로_잘리지_않는다() {
		String s = "😀".repeat(400); // 이모지 하나가 char 2칸
		String cut = ChatSearchService.firstCodePoints(s, 300);
		assertThat(cut.codePointCount(0, cut.length())).isEqualTo(300);
		assertThat(cut).isEqualTo("😀".repeat(300));
	}

	@Test
	void 짧으면_그대로_돌려준다() {
		assertThat(ChatSearchService.firstCodePoints("짧은 글", 300)).isEqualTo("짧은 글");
	}

	@Test
	void 단어는_공백으로_쪼개_2글자_이상만_대소문자_무시_중복_없이_뽑는다() {
		assertThat(ChatSearchService.keywordsOf("3만  원 이하　자전거 있어? a 자전거 Bike bike"))
				.containsExactly("3만", "이하", "자전거", "있어?", "Bike");
		assertThat(ChatSearchService.keywordsOf("a b c")).isEmpty();
	}

	// S17-e5: 최소 글자 수를 1로 주면(AI keyword용) 한 글자 단어도 쓰고, 중복·최대 10개 규칙은 같다. 빈 문자열은 단어가 아니다.
	// 한 글자는 "한글 등 ASCII가 아닌 글자"만 쓴다. 영문·숫자·기호 한 글자는 2글자 규칙처럼 버린다(두 글자 이상은 그대로 쓴다).
	@Test
	void 최소_글자_수를_1로_주면_ASCII_아닌_한_글자_단어만_뽑는다() {
		assertThat(ChatSearchService.keywordsOf("책 옷 책 A a", 1)).containsExactly("책", "옷");
		assertThat(ChatSearchService.keywordsOf("차 a 1 ? ~ 자전거 ?", 1)).containsExactly("차", "자전거");
		assertThat(ChatSearchService.keywordsOf("a 1 ? ~", 1)).isEmpty();
		assertThat(ChatSearchService.keywordsOf("ab 12 ?!", 1)).containsExactly("ab", "12", "?!"); // 두 글자 이상은 종류 상관없이 쓴다
		assertThat(ChatSearchService.keywordsOf("", 1)).isEmpty();
		assertThat(ChatSearchService.keywordsOf("   ", 1)).isEmpty();
		assertThat(ChatSearchService.keywordsOf("가 나 다 라 마 바 사 아 자 차 카 타", 1)).hasSize(10);
	}

	@Test
	void 단어는_최대_10개까지만_쓴다() {
		assertThat(ChatSearchService.keywordsOf("aa bb cc dd ee ff gg hh ii jj kk ll")).hasSize(10);
	}
}
