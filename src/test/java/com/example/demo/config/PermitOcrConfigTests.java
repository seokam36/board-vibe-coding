package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.example.demo.permit.service.NoopPermitTextReader;
import com.example.demo.permit.service.PermitTextReader;
import com.example.demo.permit.service.VisionPermitTextReader;

// PERMIT_OCR_MODE에 따라 어떤 판독기가 등록되는지, 이상한 값이면 시작이 실패하는지 검사한다 (DB 없이 PermitOcrConfig만 띄움. LlmConfigTests와 같은 방식)
class PermitOcrConfigTests {

	private static final String KEY = "SECRETKEY123";

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PermitOcrConfig.class);

	// vision 모드의 올바른 기본 설정에 추가 설정을 얹는다
	private ApplicationContextRunner vision(String... more) {
		return runner.withPropertyValues("app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=" + KEY).withPropertyValues(more);
	}

	private static String rootMessage(Throwable t) {
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return String.valueOf(t.getMessage());
	}

	// 시작 실패 + 메시지(전체 사슬)에 키 값이 없다
	private static void assertFailsWithoutKey(Throwable startupFailure, String name) {
		assertThat(startupFailure).as(name + " 시작 실패").isNotNull();
		String all = startupFailure.toString() + rootMessage(startupFailure);
		assertThat(all).doesNotContain(KEY);
	}

	@Test
	void 설정이_없으면_off이고_Noop_판독기가_등록된다() {
		runner.run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(PermitTextReader.class)).isInstanceOf(NoopPermitTextReader.class);
			assertThat(ctx).doesNotHaveBean(VisionPermitTextReader.class);
		});
	}

	@Test
	void vision_모드면_Vision_판독기만_등록된다_구글에_닿지_않아도_시작은_성공한다() {
		vision("app.permit.ocr.vision.base-url=http://127.0.0.1:1").run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(PermitTextReader.class)).isInstanceOf(VisionPermitTextReader.class);
			assertThat(ctx).doesNotHaveBean(NoopPermitTextReader.class);
		});
	}

	@Test
	void 모르는_모드면_시작이_실패하고_입력값은_출력하지_않는다() {
		for (String bad : new String[] { "abc", "Vision", "VISION", "OFF", "gemini" }) {
			runner.withPropertyValues("app.permit.ocr.mode=" + bad).run(ctx -> {
				assertThat(ctx).as(bad).hasFailed();
				String all = ctx.getStartupFailure().toString() + rootMessage(ctx.getStartupFailure());
				assertThat(all).contains("PERMIT_OCR_MODE").contains("off").contains("vision");
			});
		}
		runner.withPropertyValues("app.permit.ocr.mode=secretmode987").run(ctx -> {
			String all = ctx.getStartupFailure().toString() + rootMessage(ctx.getStartupFailure());
			assertThat(all).doesNotContain("secretmode987");
		});
	}

	// 모드가 빈 값(.env에 PERMIT_OCR_MODE= 만 적은 경우)이면 조용히 off로 넘기지 않고 시작이 실패한다
	@Test
	void 모드가_빈_값이면_시작이_실패한다() {
		runner.withPropertyValues("app.permit.ocr.mode=").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(rootMessage(ctx.getStartupFailure())).contains("PERMIT_OCR_MODE");
		});
	}

	// vision 모드 시작 로그(켜짐 한 줄)에 키가 없고 시간 상한·동시 수는 있다
	@Test
	void vision_모드_시작_로그에_키가_없다() {
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(PermitOcrConfig.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			vision().run(ctx -> assertThat(ctx).hasNotFailed());
		} finally {
			logger.detachAppender(appender);
		}
		assertThat(appender.list).hasSize(1);
		String line = appender.list.get(0).getFormattedMessage();
		assertThat(line).contains("vision").contains("10초").contains("2건").doesNotContain(KEY).doesNotContain("googleapis");
	}

	@Test
	void vision_모드인데_키가_비면_변수_이름을_알려_주며_시작이_실패한다() {
		for (String empty : new String[] { "", "   " }) {
			runner.withPropertyValues("app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=" + empty).run(ctx -> {
				assertThat(ctx).hasFailed();
				assertThat(rootMessage(ctx.getStartupFailure())).contains("VISION_API_KEY");
			});
		}
		// 키 설정 자체가 없어도(기본 빈 값) 실패한다
		runner.withPropertyValues("app.permit.ocr.mode=vision").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(rootMessage(ctx.getStartupFailure())).contains("VISION_API_KEY");
		});
	}

	@Test
	void vision_키에_공백이나_제어_글자가_있으면_시작이_실패하고_키_값은_출력하지_않는다() {
		for (String bad : new String[] { "abc def", "abc\ndef", "abc\tdef", "abc\r\ndef", "abc\u0000def" }) {
			runner.withPropertyValues("app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=" + bad).run(ctx -> {
				assertThat(ctx).as(bad).hasFailed();
				String all = ctx.getStartupFailure().toString() + rootMessage(ctx.getStartupFailure());
				assertThat(all).contains("VISION_API_KEY").doesNotContain("abc");
			});
		}
	}

	@Test
	void vision_제한_시간이_0_이하면_변수_이름을_알려_주며_시작이_실패한다() {
		for (String bad : new String[] { "0", "0s", "-5s", "-1" }) {
			vision("app.permit.ocr.vision.timeout=" + bad).run(ctx -> {
				assertFailsWithoutKey(ctx.getStartupFailure(), bad);
				assertThat(rootMessage(ctx.getStartupFailure())).contains("VISION_TIMEOUT");
			});
		}
	}

	@Test
	void vision_제한_시간에_단위_없는_숫자를_쓰면_초로_읽는다() {
		vision("app.permit.ocr.vision.timeout=7").run(ctx ->
				assertThat(ctx.getBean(PermitOcrProperties.class).vision().timeout()).isEqualTo(java.time.Duration.ofSeconds(7)));
	}

	@Test
	void vision_주소는_구글_Vision과_내_컴퓨터만_허용한다() {
		// S20-j1: 구글 Vision 주소 그대로(https, 포트 없음·443)와 내 컴퓨터만 통과. 비슷하게 생긴 남의 주소·http·다른 포트는 실패
		for (String bad : new String[] { "http://example.com", "ftp://example.com", "example.com", "http://evil.test:8080", "https://example.com",
				"https://vision.googleapis.com.evil.com", "http://vision.googleapis.com", "https://vision.googleapis.com:8443" }) {
			vision("app.permit.ocr.vision.base-url=" + bad).run(ctx -> {
				assertFailsWithoutKey(ctx.getStartupFailure(), bad);
				assertThat(rootMessage(ctx.getStartupFailure())).contains("base-url");
			});
		}
		for (String ok : new String[] { "https://vision.googleapis.com", "https://vision.googleapis.com/", "https://vision.googleapis.com:443",
				"http://127.0.0.1:8080", "http://localhost:8080" }) {
			vision("app.permit.ocr.vision.base-url=" + ok).run(ctx -> assertThat(ctx).as(ok).hasNotFailed());
		}
	}

	// S20-j1: off 모드는 주소를 검사하지 않는다(vision을 안 쓰니 아무 값이어도 시작 성공)
	@Test
	void off_모드는_vision_주소가_남의_주소여도_시작에_성공한다() {
		for (String any : new String[] { "https://example.com", "https://vision.googleapis.com.evil.com" }) {
			runner.withPropertyValues("app.permit.ocr.mode=off", "app.permit.ocr.vision.base-url=" + any)
					.run(ctx -> assertThat(ctx).as(any).hasNotFailed());
		}
	}

	@Test
	void vision_동시_상한이나_크기_상한이_0이면_시작이_실패한다() {
		vision("app.permit.ocr.vision.max-concurrent=0").run(ctx -> {
			assertFailsWithoutKey(ctx.getStartupFailure(), "concurrent");
			assertThat(rootMessage(ctx.getStartupFailure())).contains("max-concurrent");
		});
		vision("app.permit.ocr.vision.max-image-size=0B").run(ctx -> {
			assertFailsWithoutKey(ctx.getStartupFailure(), "size");
			assertThat(rootMessage(ctx.getStartupFailure())).contains("max-image-size");
		});
	}

	@Test
	void 기본값은_동시_2건_다시_만든_사진_7MB_10초다() {
		vision().run(ctx -> {
			PermitOcrProperties.Vision v = ctx.getBean(PermitOcrProperties.class).vision();
			assertThat(v.maxConcurrent()).isEqualTo(2);
			assertThat(v.maxImageSize().toBytes()).isEqualTo(7L * 1024 * 1024);
			assertThat(v.timeout()).isEqualTo(java.time.Duration.ofSeconds(10));
			assertThat(v.baseUrl()).isEqualTo("https://vision.googleapis.com");
		});
	}

	// 키가 로그·화면에 섞여 나오지 않게 toString에서 가린다
	@Test
	void 설정_toString에_키가_없다() {
		vision().run(ctx -> {
			PermitOcrProperties p = ctx.getBean(PermitOcrProperties.class);
			assertThat(p.toString()).doesNotContain(KEY).contains("****");
			assertThat(p.vision().toString()).doesNotContain(KEY).contains("****");
		});
	}
}
