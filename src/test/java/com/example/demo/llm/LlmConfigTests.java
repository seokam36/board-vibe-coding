package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.demo.config.LlmConfig;
import com.example.demo.config.LlmProperties;
import com.example.demo.llm.service.GeminiLlmClient;
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
            assertThatThrownBy(() -> client.chat("지시", "질문", Duration.ofSeconds(5))).isInstanceOf(LlmUnavailableException.class);
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
            assertThat(all).contains("off").contains("ollama").contains("gemini").doesNotContain("secretvalue123");
        });
    }

    // 소문자 off/ollama/gemini만 허용한다(앞뒤 공백은 TestPropertyValues가 잘라서 이 방식으론 확인 불가)
    @Test
    void 대소문자가_섞인_모드는_시작이_실패한다() {
        for (String bad : new String[] {"Off", "OLLAMA", "Gemini", "GEMINI"}) {
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

    // ---- S18-c: gemini 모드 ----

    private static final String KEY = "AIza-test-secret-key-123";

    // gemini 모드에 필요한 최소 설정(키)을 붙여 준다. 나머지는 기본값(모델 gemini-3.5-flash-lite, 30초, 구글 https 주소)
    private ApplicationContextRunner gemini(String... more) {
        String[] base = { "app.llm.mode=gemini", "app.llm.gemini.api-key=" + KEY };
        String[] all = new String[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return runner.withPropertyValues(all);
    }

    @Test
    void gemini_모드면_Gemini_구현이_등록되고_켜져_있다() {
        gemini().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            LlmClient client = ctx.getBean(LlmClient.class);
            assertThat(client).isInstanceOf(GeminiLlmClient.class);
            assertThat(client.isEnabled()).isTrue();
            // 호출 스레드 상한 = 동시 질문 상한(기본 2) + 2
            assertThat(((GeminiLlmClient) client).maxCallThreads()).isEqualTo(4);
        });
    }

    @Test
    void 동시_질문_상한을_바꾸면_gemini_호출_스레드_상한도_따라간다() {
        gemini("app.chat.max-concurrent=5").run(ctx ->
                assertThat(((GeminiLlmClient) ctx.getBean(LlmClient.class)).maxCallThreads()).isEqualTo(7));
    }

    @Test
    void gemini_설정의_기본값은_결정한_값이다() {
        gemini().run(ctx -> {
            LlmProperties.Gemini g = ctx.getBean(LlmProperties.class).gemini();
            assertThat(g.model()).isEqualTo("gemini-3.5-flash-lite");
            assertThat(g.timeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(g.baseUrl()).isEqualTo("https://generativelanguage.googleapis.com");
        });
    }

    @Test
    void gemini_제한_시간에_단위_없는_숫자를_쓰면_초로_읽는다() {
        gemini("app.llm.gemini.timeout=20").run(ctx ->
                assertThat(ctx.getBean(LlmProperties.class).gemini().timeout()).isEqualTo(Duration.ofSeconds(20)));
    }

    @Test
    void off_모드와_ollama_모드는_gemini_키가_비어도_뜬다() {
        runner.withPropertyValues("app.llm.mode=off", "app.llm.gemini.api-key=").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("app.llm.mode=ollama", "app.llm.gemini.api-key=", "app.llm.gemini.model=models/x", "app.llm.gemini.timeout=0")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void gemini_모드에서_키가_비면_변수_이름을_알려_주며_시작이_실패한다() {
        runner.withPropertyValues("app.llm.mode=gemini").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(rootMessage(ctx.getStartupFailure())).contains("GEMINI_API_KEY");
        });
    }

    @Test
    void gemini_키에_공백이나_제어_글자가_있으면_시작이_실패하고_키_값은_출력하지_않는다() {
        for (String bad : new String[] { "abc def", "abc\ndef", "abc\tdef", "abc\u0000def" }) {
            runner.withPropertyValues("app.llm.mode=gemini", "app.llm.gemini.api-key=" + bad).run(ctx -> {
                assertThat(ctx).as(bad).hasFailed();
                String all = ctx.getStartupFailure().toString() + rootMessage(ctx.getStartupFailure());
                assertThat(all).contains("GEMINI_API_KEY").doesNotContain("abc");
            });
        }
    }

    @Test
    void gemini_모델이_잘못되면_변수_이름을_알려_주며_시작이_실패한다() {
        for (String bad : new String[] { "", "models/gemini-3.5-flash-lite", "gemini 3.5", "gem/ini", "gemini?x" }) {
            gemini("app.llm.gemini.model=" + bad).run(ctx -> {
                assertThat(ctx).as(bad).hasFailed();
                assertThat(rootMessage(ctx.getStartupFailure())).contains("GEMINI_MODEL").doesNotContain(KEY);
            });
        }
    }

    @Test
    void gemini_제한_시간이_0_이하면_변수_이름을_알려_주며_시작이_실패한다() {
        for (String bad : new String[] { "0", "-5s" }) {
            gemini("app.llm.gemini.timeout=" + bad).run(ctx -> {
                assertThat(ctx).as(bad).hasFailed();
                assertThat(rootMessage(ctx.getStartupFailure())).contains("GEMINI_TIMEOUT").doesNotContain(KEY);
            });
        }
    }

    // 키를 담은 요청을 암호화 없는 바깥 주소로 보내지 않게: https만, 내 컴퓨터는 http도 허용(테스트의 가짜 서버용)
    @Test
    void gemini_주소는_https만_허용하고_내_컴퓨터만_http도_허용한다() {
        for (String bad : new String[] { "http://example.com", "ftp://example.com", "example.com", "http://evil.test:8080" }) {
            gemini("app.llm.gemini.base-url=" + bad).run(ctx -> {
                assertThat(ctx).as(bad).hasFailed();
                assertThat(rootMessage(ctx.getStartupFailure())).contains("base-url").doesNotContain(KEY);
            });
        }
        for (String ok : new String[] { "https://example.com", "http://127.0.0.1:8080", "http://localhost:8080" }) {
            gemini("app.llm.gemini.base-url=" + ok).run(ctx -> assertThat(ctx).as(ok).hasNotFailed());
        }
    }

    // 키가 로그·화면에 섞여 나오지 않게 toString에서 가린다
    @Test
    void 설정_객체의_toString에_키_값이_없다() {
        gemini().run(ctx -> {
            LlmProperties props = ctx.getBean(LlmProperties.class);
            assertThat(props.toString()).doesNotContain(KEY).contains("****");
            assertThat(props.gemini().toString()).doesNotContain(KEY).contains("model=gemini-3.5-flash-lite");
        });
    }

    @Test
    void 키가_없으면_toString에_없음으로_나온다() {
        runner.run(ctx -> assertThat(ctx.getBean(LlmProperties.class).gemini().toString()).contains("(없음)"));
    }

    // 모드별 시간 예산: gemini = GEMINI_TIMEOUT, ollama(와 off) = OLLAMA_TIMEOUT
    @Test
    void 모드에_맞는_예산과_변수_이름을_돌려준다() {
        gemini("app.llm.gemini.timeout=20s", "app.llm.ollama.timeout=50s").run(ctx -> {
            LlmProperties p = ctx.getBean(LlmProperties.class);
            assertThat(p.budget()).isEqualTo(Duration.ofSeconds(20));
            assertThat(p.budgetVariable()).isEqualTo("GEMINI_TIMEOUT");
        });
        runner.withPropertyValues("app.llm.mode=ollama", "app.llm.gemini.timeout=20s", "app.llm.ollama.timeout=50s").run(ctx -> {
            LlmProperties p = ctx.getBean(LlmProperties.class);
            assertThat(p.budget()).isEqualTo(Duration.ofSeconds(50));
            assertThat(p.budgetVariable()).isEqualTo("OLLAMA_TIMEOUT");
        });
    }

    // off는 가짜 AI 테스트 사정으로 ollama 값을 쓰고, 모르는 모드는 조용히 ollama 값을 주지 않고 예외다(LlmConfig가 막아서 실제론 안 닿는다)
    @Test
    void off는_올라마_예산이고_모르는_모드는_예외다() {
        LlmProperties off = new LlmProperties("off", new LlmProperties.Ollama("http://localhost:11434", "m", Duration.ofSeconds(50)),
                new LlmProperties.Gemini("https://x.example", "", "m", Duration.ofSeconds(20)));
        assertThat(off.budget()).isEqualTo(Duration.ofSeconds(50));
        assertThat(off.budgetVariable()).isEqualTo("OLLAMA_TIMEOUT");

        LlmProperties weird = new LlmProperties("weird", off.ollama(), off.gemini());
        assertThatThrownBy(weird::budget).isInstanceOf(IllegalStateException.class).hasMessageNotContaining("weird");
        assertThatThrownBy(weird::budgetVariable).isInstanceOf(IllegalStateException.class);
    }

    private static String rootMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) sb.append(t.getMessage()).append('\n');
        return sb.toString();
    }
}
