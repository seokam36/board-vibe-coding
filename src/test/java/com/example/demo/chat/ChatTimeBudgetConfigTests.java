package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

import com.example.demo.TestcontainersConfiguration;

// S17-e5: 예산 설정값(OLLAMA_TIMEOUT = app.llm.ollama.timeout)을 바꾸면 양식 호출의 제한 시간도 따라 바뀌는지(10초로 시험).
@SpringBootTest(properties = "app.llm.ollama.timeout=10s")
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatTimeBudgetConfigTests extends ChatTestBase {

	@Test
	void 설정이_10초면_양식_호출도_10초를_받는다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");

		질문("자전거 있어?");

		assertThat(llm.jsonCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(10));
		assertThat(llm.chatCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(10)); // 가짜 시계는 안 흐르니 정확히 예산 그대로
	}

	@Test
	void 설정이_10초일_때_양식_호출이_10초_걸리면_504다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.jsonTakes(Duration.ofSeconds(10));

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(504);
		assertThat(llm.chatCalls).isEmpty();
	}
}
