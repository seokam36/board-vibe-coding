package com.example.demo.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.chat.service.ChatService;
import com.example.demo.config.LlmProperties;
import com.example.demo.llm.service.LlmClient;
import com.example.demo.llm.service.OffLlmClient;

// S17-i(조건 5): ChatService가 시간 예산 폴백(60초)을 지웠다. AI를 끈 모드(off)에서 OLLAMA_TIMEOUT=0 같은 이상한 값이 들어와도
// 앱이 정상으로 뜨고, 질문은 예산을 읽기 전에 503으로 끝나는지 본다(0 이하 거부는 ollama 모드에서만 한다).
@SpringBootTest(properties = { "app.llm.mode=off", "app.llm.ollama.timeout=0" })
@Import(TestcontainersConfiguration.class)
class ChatOffModeZeroTimeoutTests {

	@Autowired ChatService chatService;
	@Autowired LlmClient llmClient;
	@Autowired LlmProperties llmProperties;

	@Test
	void off_모드에서는_OLLAMA_TIMEOUT이_0이어도_앱이_뜨고_Off_구현이_등록된다() {
		assertThat(llmClient).isInstanceOf(OffLlmClient.class);
		assertThat(llmProperties.ollama().timeout().isZero()).isTrue(); // 0이 그대로 들어왔는데도 시작이 성공했다
	}

	@Test
	void off_모드에서는_예산이_0이어도_질문이_예산을_읽기_전에_503으로_끝난다() {
		assertThatThrownBy(() -> chatService.ask(1L, "자전거 있어?"))
				.isInstanceOfSatisfying(ResponseStatusException.class,
						e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
	}
}
