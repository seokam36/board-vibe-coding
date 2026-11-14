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

	@Test
	void 단어는_최대_10개까지만_쓴다() {
		assertThat(ChatSearchService.keywordsOf("aa bb cc dd ee ff gg hh ii jj kk ll")).hasSize(10);
	}
}
