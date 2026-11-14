package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.llm.service.LlmClient;
import com.example.demo.llm.service.OllamaLlmClient;

// 앱 전체(DB·Redis 포함) 컨텍스트에서도 LLM_MODE 규칙이 맞는지 검사한다.
@SpringBootTest(properties = {"app.llm.mode=ollama", "app.llm.ollama.base-url=http://127.0.0.1:1"})
@Import(TestcontainersConfiguration.class)
class LlmFullContextTests {

    @Autowired
    LlmClient llmClient;

    @Test
    void 앱_전체에서_ollama_모드면_Ollama_구현이_등록되고_서버가_꺼져_있어도_시작된다() {
        assertThat(llmClient).isInstanceOf(OllamaLlmClient.class);
    }
}
