package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.demo.TestcontainersConfiguration;

// S17-e5: 질문 하나 전체의 AI 시간 예산(기본 OLLAMA_TIMEOUT 60초)을 양식 호출·답 호출이 나눠 쓰는지.
// 가짜 AI의 "N초 걸린 척"(가짜 시계를 앞으로 돌림)으로 확인하므로 진짜로 기다리지 않는다.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, ScriptedLlmClient.Config.class })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class ChatTimeBudgetTests extends ChatTestBase {

	// 가짜 시계는 테스트 시작 때 시각을 고정하고 "걸린 척"으로만 움직인다(진짜 시간이 안 섞임). 그래서 남은 시간을 오차 없이 정확히 확인한다.

	@Test
	void 양식_호출이_받은_제한_시간은_전체_예산_60초다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");

		질문("자전거 있어?");

		assertThat(llm.jsonCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(60));
	}

	@Test
	void 양식_호출이_20초_걸리면_답_호출은_정확히_40초를_받는다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.jsonTakes(Duration.ofSeconds(20));

		MockHttpServletResponse r = 질문("자전거 있어?");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(llm.chatCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(40)); // 60 - 20
	}

	// 시스템 시각이 뒤로 갔다면(벽시계) 지난 시간이 음수가 된다. 그래도 답 호출은 예산(60초)보다 크게 받지 않는다.
	@Test
	void 시계가_뒤로_가도_답_호출은_예산_60초를_넘지_않는다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.jsonTakes(Duration.ofSeconds(-30)); // 시계가 30초 뒤로

		MockHttpServletResponse r = 질문("자전거 있어?");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(llm.chatCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(60)); // 90초가 아니라 예산 60초로 잘림
	}

	@Test
	void 양식_호출이_예산을_다_쓰면_답_호출은_0번이고_504이며_원인_메시지가_없다(CapturedOutput output) throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.jsonTakes(Duration.ofSeconds(60));

		MockHttpServletResponse r = 질문("자전거 있어?");

		assertThat(r.getStatus()).isEqualTo(504);
		assertThat(llm.chatCalls).isEmpty();
		assertThat(r.getContentAsString()).doesNotContain("예산"); // 응답 본문에 원인 메시지 없음
		assertThat(output.getAll()).contains("LlmTimeoutException").doesNotContain("예산"); // 로그엔 예외 종류만
	}

	@Test
	void 남은_시간이_1초_미만이면_답_호출을_하지_않고_504다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.jsonTakes(Duration.ofMillis(59_500)); // 남은 0.5초

		assertThat(질문("자전거 있어?").getStatus()).isEqualTo(504);
		assertThat(llm.chatCalls).isEmpty();
	}

	@Test
	void 남은_시간이_충분하면_답_호출을_하고_그_시간만_받는다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");
		llm.jsonTakes(Duration.ofSeconds(57)); // 남은 3초(1초 이상)

		MockHttpServletResponse r = 질문("자전거 있어?");

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(llm.chatCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(3));
	}

	// 양식 호출이 예산을 다 써도, 어차피 답 호출이 없는 갈래(0건·keyword 없음)는 시간 때문에 504가 되지 않는다(정상 200).
	@Test
	void 답_호출이_없는_갈래는_양식_호출이_오래_걸려도_200이다() throws Exception {
		올리기("노트북 팝니다", "SALE", 1000);
		llm.jsonTakes(Duration.ofSeconds(60));

		// keyword 없음(S17-e5: 예전 needSearch=false 자리)과 검색 0건, 둘 다 답 호출 없이 200
		for (String form : new String[] {"{}", "{\"keyword\":\"자전거\"}"}) {
			llm.jsonReturns(form);
			assertThat(질문("안녕").getStatus()).as(form).isEqualTo(200);
			assertThat(llm.chatCalls).as(form).isEmpty();
		}
	}

	// 앞 테스트가 가짜 시계를 돌려 놨어도 reset()이 되돌려서 시간이 이월되지 않는다.
	@Test
	void 이전_테스트의_걸린_척이_이월되지_않는다() throws Exception {
		올리기("자전거 팝니다", "SALE", 1000);
		llm.jsonReturns("{\"keyword\":\"자전거\"}");

		질문("자전거 있어?");

		assertThat(llm.chatCalls.get(0).timeout()).isEqualTo(Duration.ofSeconds(60)); // 걸린 척이 없으니 예산 그대로
	}
}
