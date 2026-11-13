package com.example.demo.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.example.demo.message.service.RecentMessages;

// S14-g: RecentMessages.trim 단위 테스트 (DB 없이). 입력은 id 내림차순(최신이 앞)이다.
class RecentMessagesTests {

	// "n, n-1, ..., 1" 처럼 최신이 앞인 목록을 만든다
	private List<Integer> 최신순(int n) {
		List<Integer> list = new ArrayList<>(IntStream.rangeClosed(1, n).boxed().toList());
		Collections.reverse(list);
		return list;
	}

	// 무엇: 빈 목록이면 빈 결과 + hasMore=false
	@Test
	void trim_빈_목록은_빈_결과이고_hasMore는_false다() {
		RecentMessages.Result<Integer> r = RecentMessages.trim(List.of());
		assertThat(r.items()).isEmpty();
		assertThat(r.hasMore()).isFalse();
	}

	// 무엇: 정확히 200개면 자르지 않고 hasMore=false, 오름차순으로 뒤집힌다
	@Test
	void trim_200개는_전부_남고_hasMore는_false이며_오름차순이다() {
		RecentMessages.Result<Integer> r = RecentMessages.trim(최신순(200));
		assertThat(r.items()).hasSize(200);
		assertThat(r.items().get(0)).isEqualTo(1);
		assertThat(r.items().get(199)).isEqualTo(200);
		assertThat(r.hasMore()).isFalse();
	}

	// 무엇: 201개면 가장 오래된 1개(1번)를 버리고 200개 + hasMore=true, 오름차순
	@Test
	void trim_201개는_가장_오래된_1개를_버리고_hasMore는_true다() {
		RecentMessages.Result<Integer> r = RecentMessages.trim(최신순(201));
		assertThat(r.items()).hasSize(200);
		assertThat(r.items().get(0)).isEqualTo(2);
		assertThat(r.items().get(199)).isEqualTo(201);
		assertThat(r.items()).isSorted();
		assertThat(r.hasMore()).isTrue();
	}

	// 무엇: 상수 값(LIMIT 200, FETCH_SIZE 201)이 명세와 같다
	@Test
	void 상수는_LIMIT_200_FETCH_SIZE_201이다() {
		assertThat(RecentMessages.LIMIT).isEqualTo(200);
		assertThat(RecentMessages.FETCH_SIZE).isEqualTo(201);
	}
}
