package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.demo.TestcontainersConfiguration;

// S18-c: gemini 모드의 질문 전체 예산은 GEMINI_TIMEOUT이다(OLLAMA_TIMEOUT 60초가 아니다).
// 진짜 제미나이는 부르지 않는다: 가짜 AI(ScriptedLlmClient)가 @Primary로 끼어들고, 가짜 시계로 "N초 걸린 척"만 한다.
// (gemini 모드 설정만 켜 두므로 키·모델 검사는 실제로 통과해야 앱이 뜬다.)
@SpringBootTest(properties = { "app.llm.mode=gemini", "app.llm.gemini.api-key=test-key-not-real",
		"app.llm.gemini.timeout=20s", "app.llm.ollama.timeout=60s" })
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
class ChatGeminiBudgetTests extends ChatTestBase {

	@Test
	void 양식_호출이_받은_제한_시간은_GEMINI_TIMEOUT_20초다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");

		질문("자전거 있어?");

		assertThat(llm.jsonCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(20));
	}

	@Test
	void 양식_호출이_8초_걸리면_답_호출은_정확히_12초를_받는다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.jsonTakes(Duration.ofSeconds(8));

		MockHttpServletResponse r = 질문("자전거 있어?");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(llm.chatCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(12)); // 20 - 8
	}

	@Test
	void 양식_호출이_예산_20초를_다_쓰면_답_호출은_0번이고_504다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.jsonTakes(Duration.ofSeconds(20));

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(504);
		assertThat(llm.chatCalls).isEmpty();
	}
}
