package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.demo.config.LlmConfig;
import com.example.demo.config.LlmProperties;
import com.example.demo.llm.service.LlmClient;
import com.example.demo.llm.service.LlmUnavailableException;
import com.example.demo.llm.service.OffLlmClient;
import com.example.demo.llm.service.OllamaLlmClient;

// LLM_MODE에 따라 어떤 구현이 등록되는지, 이상한 값이면 시작이 실패하는지 검사한다 (DB 없이 LlmConfig만 띄움)
class LlmConfigTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(LlmConfig.class);

    @Test
    void 설정이_없으면_off_구현이고_호출하면_사용_불가() {
        runner.run(ctx -> {
            LlmClient client = ctx.getBean(LlmClient.class);
            assertThat(client).isInstanceOf(OffLlmClient.class);
            assertThatThrownBy(() -> client.chat("지시", "질문")).isInstanceOf(LlmUnavailableException.class);
        });
    }

    @Test
    void ollama_모드면_Ollama_서버가_꺼져_있어도_시작이_성공한다() {
        runner.withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.base-url=http://127.0.0.1:1")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(LlmClient.class)).isInstanceOf(OllamaLlmClient.class);
                });
    }

    @Test
    void 이상한_모드면_시작이_실패하고_허용_값을_알려_주되_입력값은_출력하지_않는다() {
        runner.withPropertyValues("app.llm.mode=secretvalue123").run(ctx -> {
            assertThat(ctx).hasFailed();
            String all = ctx.getStartupFailure().toString() + rootMessage(ctx.getStartupFailure());
            assertThat(all).contains("off").contains("ollama").doesNotContain("secretvalue123");
        });
    }

    // 소문자 off/ollama만 허용한다(앞뒤 공백은 TestPropertyValues가 잘라서 이 방식으론 확인 불가)
    @Test
    void 대소문자가_섞인_모드는_시작이_실패한다() {
        for (String bad : new String[] {"Off", "OLLAMA"}) {
            runner.withPropertyValues("app.llm.mode=" + bad).run(ctx -> assertThat(ctx).as(bad).hasFailed());
        }
    }

    @Test
    void 제한_시간에_단위_없는_숫자를_쓰면_초로_읽는다() {
        runner.withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.timeout=60").run(ctx ->
                assertThat(ctx.getBean(LlmProperties.class).ollama().timeout()).isEqualTo(Duration.ofSeconds(60)));
    }

    @Test
    void 제한_시간이_0이면_변수_이름을_알려_주며_시작이_실패한다() {
        runner.withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.timeout=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("OLLAMA_TIMEOUT");
        });
    }

    @Test
    void 모델이_비어_있으면_변수_이름을_알려_주며_시작이_실패한다() {
        runner.withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.model=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("OLLAMA_MODEL");
        });
    }

    @Test
    void 주소가_비어_있으면_변수_이름을_알려_주며_시작이_실패한다() {
        runner.withPropertyValues("app.llm.mode=ollama", "app.llm.ollama.base-url=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("OLLAMA_BASE_URL");
        });
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) sb.append(t.getMessage()).append('\n');
        return sb.toString();
    }
}
