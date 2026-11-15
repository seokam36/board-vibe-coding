package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

// 챗봇 설정 값 검사(S17-f2 리뷰 반영). DB 없이 ChatConfig만 띄운다(LlmConfigTests와 같은 방식).
class ChatConfigTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(ChatConfig.class);

	@Test
	void 설정이_없으면_기본값으로_시작한다() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			ChatProperties p = ctx.getBean(ChatProperties.class);
			assertThat(p.maxConcurrent()).isEqualTo(2);
			assertThat(p.slotWait()).isEqualTo(Duration.ofSeconds(5));
		});
	}

	@Test
	void 동시_질문_수가_0이면_설정_이름을_알려_주며_시작이_실패한다() {
		runner.withPropertyValues("app.chat.max-concurrent=0").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(rootMessage(ctx.getStartupFailure())).contains("app.chat.max-concurrent");
		});
	}

	@Test
	void 자리_대기_시간이_음수이면_설정_이름을_알려_주며_시작이_실패한다() {
		runner.withPropertyValues("app.chat.slot-wait=-1s").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(rootMessage(ctx.getStartupFailure())).contains("app.chat.slot-wait").doesNotContain("-1s");
		});
	}

	@Test
	void 자리_대기_시간이_0이어도_시작한다() {
		runner.withPropertyValues("app.chat.slot-wait=0s").run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	void ollama_모드에서_자리_대기가_질문_전체_시간_이상이면_시작이_실패한다() {
		runner.withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.timeout=10s", "app.chat.slot-wait=10s").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(rootMessage(ctx.getStartupFailure())).contains("app.chat.slot-wait").contains("OLLAMA_TIMEOUT");
		});
	}

	@Test
	void ollama_모드에서_자리_대기가_질문_전체_시간보다_짧으면_시작한다() {
		runner.withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.timeout=10s", "app.chat.slot-wait=9s")
				.run(ctx -> assertThat(ctx).hasNotFailed());
	}

	// S18-c: gemini 모드의 예산은 GEMINI_TIMEOUT이다(OLLAMA_TIMEOUT이 아니다). 문구도 그 변수 이름이다.
	@Test
	void gemini_모드에서_자리_대기가_질문_전체_시간_이상이면_GEMINI_TIMEOUT을_알려_주며_시작이_실패한다() {
		runner.withPropertyValues("app.llm.mode=gemini", "app.llm.gemini.timeout=10s", "app.llm.ollama.timeout=100s", "app.chat.slot-wait=10s")
				.run(ctx -> {
					assertThat(ctx).hasFailed();
					String msg = rootMessage(ctx.getStartupFailure());
					assertThat(msg).contains("app.chat.slot-wait").contains("GEMINI_TIMEOUT").doesNotContain("OLLAMA_TIMEOUT");
				});
	}

	// ollama 예산(100초)이 아니라 gemini 예산(10초)과 비교하므로, 9초 대기는 통과하고 기본 5초 대기도 통과한다.
	@Test
	void gemini_모드에서_자리_대기가_질문_전체_시간보다_짧으면_시작한다() {
		runner.withPropertyValues("app.llm.mode=gemini", "app.llm.gemini.timeout=10s", "app.chat.slot-wait=9s")
				.run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	void gemini_모드_기본값_30초_예산에서도_기본_자리_대기_5초는_시작한다() {
		runner.withPropertyValues("app.llm.mode=gemini").run(ctx -> assertThat(ctx).hasNotFailed());
	}

	@Test
	void ollama_모드의_실패_문구에는_GEMINI_TIMEOUT이_없다() {
		runner.withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.timeout=10s", "app.chat.slot-wait=10s").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(rootMessage(ctx.getStartupFailure())).contains("OLLAMA_TIMEOUT").doesNotContain("GEMINI_TIMEOUT");
		});
	}

	// off 모드는 질문이 AI 없이 바로 503으로 끝나 예산을 쓰지 않으므로 예산 비교를 건너뛴다.
	@Test
	void off_모드에서는_자리_대기가_예산_이상이어도_시작한다() {
		runner.withPropertyValues("app.llm.mode=off", "app.llm.ollama.timeout=10s", "app.chat.slot-wait=30s")
				.run(ctx -> assertThat(ctx).hasNotFailed());
	}

	private static String rootMessage(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (; t != null; t = t.getCause()) sb.append(t.getMessage()).append('\n');
		return sb.toString();
	}
}
