package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.demo.market.service.ImageSafetyChecker;
import com.example.demo.market.service.NoopImageSafetyChecker;
import com.example.demo.market.service.VisionImageSafetyChecker;
import com.example.demo.market.service.MarketSafeSearchDailyLimiter;

// S30-a: MARKET_SAFESEARCH_MODE 설정 검사(시작 실패·빈 선택). DB 없이 MarketSafeSearchConfig만 띄운다(PermitOcrConfigTests와 같은 방식).
class MarketSafeSearchConfigTests {

    private static final String KEY = "SECRETKEY123";

    // vision 모드의 하루 상한 부품은 가짜로 채운다(Redis 없이)
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MarketSafeSearchConfig.class)
            .withBean(MarketSafeSearchDailyLimiter.class, () -> mock(MarketSafeSearchDailyLimiter.class));

    private ApplicationContextRunner vision(String... more) {
        return runner.withPropertyValues("app.market.safesearch.mode=vision", "app.market.safesearch.api-key=" + KEY,
                "app.market.safesearch.base-url=http://127.0.0.1:1").withPropertyValues(more);
    }

    // 시작 실패 사슬 전체의 글자
    private static String allMessages(Throwable t) {
        StringBuilder sb = new StringBuilder();
        for (; t != null; t = t.getCause()) {
            sb.append(t).append(' ').append(t.getMessage()).append(' ');
        }
        return sb.toString();
    }

    // 완료 조건 1
    @Test
    void 모드가_off_vision이_아니면_시작이_실패하고_그_값은_메시지에_없다() {
        for (String bad : new String[] { "abc", "Vision", "VISION", "OFF", "gemini" }) {
            runner.withPropertyValues("app.market.safesearch.mode=" + bad).run(ctx -> {
                assertThat(ctx).as(bad).hasFailed();
                String all = allMessages(ctx.getStartupFailure());
                assertThat(all).contains("MARKET_SAFESEARCH_MODE").contains("off").contains("vision");
            });
        }
        runner.withPropertyValues("app.market.safesearch.mode=secretmode987").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(allMessages(ctx.getStartupFailure())).doesNotContain("secretmode987");
        });
    }

    @Test
    void 설정이_없으면_off이고_Noop_검사기가_등록된다() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ImageSafetyChecker.class)).isInstanceOf(NoopImageSafetyChecker.class);
            assertThat(ctx).doesNotHaveBean(VisionImageSafetyChecker.class);
        });
    }

    @Test
    void vision_모드면_Vision_검사기만_등록되고_구글에_닿지_않아도_시작은_성공한다() {
        vision().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(ImageSafetyChecker.class)).isInstanceOf(VisionImageSafetyChecker.class);
            assertThat(ctx).doesNotHaveBean(NoopImageSafetyChecker.class);
        });
    }

    // 완료 조건 2
    @Test
    void vision인데_VISION_API_KEY가_비면_시작이_실패한다() {
        runner.withPropertyValues("app.market.safesearch.mode=vision").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(allMessages(ctx.getStartupFailure())).contains("VISION_API_KEY");
        });
        runner.withPropertyValues("app.market.safesearch.mode=vision", "app.market.safesearch.api-key=   ").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(allMessages(ctx.getStartupFailure())).contains("VISION_API_KEY");
        });
    }

    @Test
    void off_모드에서는_키가_없어도_시작된다() {
        runner.withPropertyValues("app.market.safesearch.mode=off", "app.market.safesearch.api-key=").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    // 추가: 기준 등급 오타
    @Test
    void 기준_등급_글자가_오타면_시작이_실패하고_그_값은_메시지에_없다() {
        for (String setting : new String[] { "adult.block", "adult.flag", "violence.block", "violence.flag", "racy.block", "racy.flag" }) {
            // 모드가 off여도 오타는 일찍 잡는다
            runner.withPropertyValues("app.market.safesearch." + setting + "=LIKLEYTYPO987").run(ctx -> {
                assertThat(ctx).as(setting).hasFailed();
                String all = allMessages(ctx.getStartupFailure());
                assertThat(all).contains(setting).doesNotContain("LIKLEYTYPO987");
            });
        }
        // UNKNOWN도 기준으로는 쓸 수 없다
        runner.withPropertyValues("app.market.safesearch.adult.flag=UNKNOWN").run(ctx -> assertThat(ctx).hasFailed());
        vision("app.market.safesearch.racy.flag=LIKLEYTYPO987").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void 기준_등급이_OFF_소문자_정상값이면_시작된다() {
        runner.withPropertyValues("app.market.safesearch.adult.block=off", "app.market.safesearch.adult.flag=possible",
                "app.market.safesearch.racy.flag=VERY_LIKELY").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 하루_상한이_음수면_시작이_실패하고_0과_양수는_정상이다() {
        runner.withPropertyValues("app.market.safesearch.per-day=-1").run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues("app.market.safesearch.per-day=0").run(ctx -> assertThat(ctx).hasNotFailed());
        runner.withPropertyValues("app.market.safesearch.per-day=30").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 허용되지_않는_주소면_시작이_실패하고_키는_메시지에_없다() {
        vision("app.market.safesearch.base-url=http://evil.example.com").run(ctx -> {
            assertThat(ctx).hasFailed();
            // 리뷰 8: 어떤 설정이 문제인지 중고 쪽 이름으로 알려 준다(허가서 이름이 아님)
            assertThat(allMessages(ctx.getStartupFailure())).contains("app.market.safesearch.base-url")
                    .doesNotContain("app.permit.ocr.vision.base-url").doesNotContain(KEY);
        });
    }

    @Test
    void 키에_공백이_있으면_시작이_실패하고_키는_메시지에_없다() {
        runner.withPropertyValues("app.market.safesearch.mode=vision", "app.market.safesearch.api-key=AB CD" + KEY).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(allMessages(ctx.getStartupFailure())).doesNotContain(KEY);
        });
    }
}
