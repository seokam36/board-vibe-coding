package com.example.demo.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.example.demo.config.LlmConfig;
import com.example.demo.config.LlmProperties;

// S18-c 점검: 환경변수(APP_LLM_GEMINI_BASEURL 등)로 주소를 몰래 바꾸려 해도 https 검사가 막는지,
// 그리고 시작 실패의 예외 사슬 전체(스택 트레이스)에 키 값이 없는지 본다. (DB 없이 LlmConfig만 띄움)
class LlmGeminiEnvBindingTests {

    private static final String KEY = "SECRETKEY-with space-ZQXTAIL";

    // 진짜 환경변수처럼 취급되는 속성 소스(느슨한 바인딩 규칙 적용)를 실행기에 끼운다
    private ApplicationContextRunner withEnv(Map<String, Object> env) {
        return new ApplicationContextRunner().withUserConfiguration(LlmConfig.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("systemEnvironment", env)));
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    @Test
    void 환경변수_APP_LLM_GEMINI_BASEURL이_주소를_덮어써도_http_바깥_주소는_시작이_실패한다() {
        withEnv(Map.of("APP_LLM_MODE", "gemini", "APP_LLM_GEMINI_APIKEY", "goodkey123",
                "APP_LLM_GEMINI_BASEURL", "http://evil.example")).run(ctx -> {
                    // 덮어써지는지(바인딩) 자체는 사실이고, 그래서 검사가 막아야 한다
                    assertThat(ctx).hasFailed();
                    assertThat(stackTrace(ctx.getStartupFailure())).contains("base-url").doesNotContain("goodkey123");
                });
    }

    @Test
    void 환경변수_APP_LLM_GEMINI_BASE_URL_밑줄형도_http_바깥_주소는_막힌다() {
        withEnv(Map.of("APP_LLM_MODE", "gemini", "APP_LLM_GEMINI_APIKEY", "goodkey123",
                "APP_LLM_GEMINI_BASE_URL", "http://evil.example")).run(ctx -> {
                    // 밑줄형(BASE_URL)도 바인딩되므로 http 바깥 주소는 시작 실패다(실제로 돌려 확인함). 키는 새지 않는다.
                    assertThat(ctx).hasFailed();
                    assertThat(stackTrace(ctx.getStartupFailure())).contains("base-url").doesNotContain("goodkey123");
                });
    }

    @Test
    void 환경변수로_https_주소를_주면_그대로_쓰인다() {
        withEnv(Map.of("APP_LLM_MODE", "gemini", "APP_LLM_GEMINI_APIKEY", "goodkey123",
                "APP_LLM_GEMINI_BASEURL", "https://proxy.example.com")).run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(LlmProperties.class).gemini().baseUrl()).isEqualTo("https://proxy.example.com");
                });
    }

    @Test
    void 키에_공백이_섞여_시작이_실패해도_예외_사슬_전체에_키_조각이_없다() {
        withEnv(Map.of("APP_LLM_MODE", "gemini", "APP_LLM_GEMINI_APIKEY", KEY)).run(ctx -> {
            assertThat(ctx).hasFailed();
            String all = stackTrace(ctx.getStartupFailure());
            assertThat(all).contains("GEMINI_API_KEY").doesNotContain("SECRETKEY").doesNotContain("with space").doesNotContain("ZQXTAIL");
        });
    }
}
