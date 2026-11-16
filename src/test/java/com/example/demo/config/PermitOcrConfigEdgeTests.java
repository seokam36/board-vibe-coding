package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;

import com.example.demo.permit.service.NoopPermitTextReader;
import com.example.demo.permit.service.PermitTextReader;
import com.example.demo.permit.service.VisionPermitTextReader;
import com.sun.net.httpserver.HttpServer;

// S20-e2 경계 테스트: 모드 값의 대소문자·공백, 주소 모양, 앱 종료 때 정리, off일 때의 느슨함 (DB 없이 PermitOcrConfig만 띄움)
class PermitOcrConfigEdgeTests {

	private static final String KEY = "EDGEKEY-never-real-4455";

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(PermitOcrConfig.class);

	private static String rootMessage(Throwable t) {
		while (t.getCause() != null) {
			t = t.getCause();
		}
		return String.valueOf(t.getMessage());
	}

	// 환경변수처럼 앞뒤 공백을 다듬지 않고 그대로 넣는 설정 (withPropertyValues는 다듬을 수 있어서 직접 만든다)
	private ApplicationContextRunner raw(Map<String, Object> props) {
		return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("raw", props)));
	}

	private ApplicationContextRunner visionWithUrl(String url) {
		return runner.withPropertyValues("app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=" + KEY,
				"app.permit.ocr.vision.base-url=" + url);
	}

	// 현재 동작: 대소문자가 다르거나 공백이 낀 모드는 조용히 off로 가지 않고 시작이 실패한다(값은 출력 안 함)
	@Test
	void 모드_값이_VISION이거나_앞뒤_공백이_있으면_조용히_off가_되지_않고_시작이_실패한다() {
		for (String bad : new String[] { "VISION", "Vision", " vision ", "vision ", " vision", "off ", " OFF" }) {
			raw(Map.of("app.permit.ocr.mode", bad, "app.permit.ocr.vision.api-key", KEY)).run(ctx -> {
				assertThat(ctx).as("[" + bad + "]").hasFailed();
				String all = ctx.getStartupFailure().toString() + rootMessage(ctx.getStartupFailure());
				assertThat(all).contains("PERMIT_OCR_MODE").doesNotContain(KEY);
			});
		}
	}

	@Test
	void 주소_끝_슬래시와_https_localhost와_http_IPv6_loopback은_시작에_성공한다() {
		for (String ok : new String[] { "https://example.com/", "https://localhost", "https://localhost/", "http://[::1]:8080",
				"http://localhost:1/", "https://vision.googleapis.com" }) {
			visionWithUrl(ok).run(ctx -> {
				assertThat(ctx).as(ok).hasNotFailed();
				assertThat(ctx.getBean(PermitTextReader.class)).isInstanceOf(VisionPermitTextReader.class);
			});
		}
	}

	// 허용 목록(localhost, 127.0.0.1, [::1])에 없는 http는 loopback이어도 거절한다. 위장 주소도 거절한다.
	@Test
	void 목록_밖의_http_주소는_loopback처럼_보여도_시작이_실패하고_메시지에_키가_없다() {
		for (String bad : new String[] { "http://127.0.0.2", "http://127.0.0.2:8080", "http://localhost.evil.com", "http://127.0.0.1.evil.com",
				"http://0.0.0.0:8080", "https:///only-path", "http://", "" }) {
			visionWithUrl(bad).run(ctx -> {
				assertThat(ctx).as("[" + bad + "]").hasFailed();
				String all = ctx.getStartupFailure().toString() + rootMessage(ctx.getStartupFailure());
				assertThat(all).contains("base-url").doesNotContain(KEY);
			});
		}
	}

	// 사용자 정보가 낀 주소: 호스트는 evil.com 이다. http로 loopback이라고 속여도 막아야 한다.
	@Test
	void 사용자정보로_loopback을_속인_http_주소는_시작이_실패한다() {
		visionWithUrl("http://127.0.0.1@evil.com").run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(rootMessage(ctx.getStartupFailure())).contains("base-url");
		});
	}

	@Test
	void off_모드는_VISION_API_KEY만_있어도_정상_시작하고_Noop이다() {
		runner.withPropertyValues("app.permit.ocr.vision.api-key=" + KEY).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(PermitTextReader.class)).isInstanceOf(NoopPermitTextReader.class);
			assertThat(ctx).doesNotHaveBean(VisionPermitTextReader.class);
		});
		runner.withPropertyValues("app.permit.ocr.mode=off", "app.permit.ocr.vision.api-key=" + KEY).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(PermitTextReader.class).read(new byte[] { 1 }, "image/jpeg")).isEqualTo(Optional.empty());
		});
	}

	// off일 때는 vision 쪽 설정이 이상해도 검사하지 않는다(현재 동작 확인)
	@Test
	void off_모드는_vision_설정이_이상해도_시작에_성공한다() {
		runner.withPropertyValues("app.permit.ocr.mode=off", "app.permit.ocr.vision.timeout=0", "app.permit.ocr.vision.base-url=http://example.com")
				.run(ctx -> assertThat(ctx).hasNotFailed());
	}

	// 앱이 꺼질 때 destroyMethod=close가 불려 호출 스레드가 사라지고, 닫힌 뒤 read는 빈 값이다
	@Test
	void vision_모드에서_컨텍스트가_닫히면_판독기가_닫히고_호출_스레드가_정리된다() throws Exception {
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", ex -> {
			byte[] b = "{\"responses\":[{}]}".getBytes();
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(200, b.length);
			ex.getResponseBody().write(b);
			ex.close();
		});
		server.start();
		try {
			Set<Thread> before = visionThreads();
			VisionPermitTextReader[] holder = new VisionPermitTextReader[1];
			Set<Thread>[] created = new Set[1];
			runner.withPropertyValues("app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=" + KEY,
					"app.permit.ocr.vision.base-url=http://127.0.0.1:" + server.getAddress().getPort()).run(ctx -> {
						holder[0] = ctx.getBean(VisionPermitTextReader.class);
						holder[0].read(new byte[] { 1, 2, 3 }, "image/jpeg"); // 작업 스레드가 하나 생긴다
						created[0] = visionThreads();
						created[0].removeAll(before);
					});
			// run()이 끝나면 컨텍스트가 닫힌다 -> close() 호출
			assertThat(created[0]).as("판독 중 새로 생긴 vision-reader 스레드").isNotEmpty();
			long deadline = System.currentTimeMillis() + 3000;
			while (System.currentTimeMillis() < deadline && created[0].stream().anyMatch(Thread::isAlive)) {
				Thread.sleep(50);
			}
			assertThat(created[0].stream().filter(Thread::isAlive).count()).as("닫힌 뒤 살아 있는 스레드").isZero();
			long t0 = System.nanoTime();
			assertThat(holder[0].read(new byte[] { 1, 2, 3 }, "image/jpeg")).isEmpty();
			assertThat((System.nanoTime() - t0) / 1_000_000).as("닫힌 판독기는 바로 빈 값(ms)").isLessThan(500);
		} finally {
			server.stop(0);
		}
	}

	private static Set<Thread> visionThreads() {
		return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().startsWith("vision-reader-"))
				.collect(Collectors.toSet());
	}
}
