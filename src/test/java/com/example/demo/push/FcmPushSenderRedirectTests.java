package com.example.demo.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.example.demo.push.service.FcmPushSender;
import com.example.demo.push.service.PushResult;
import com.sun.net.httpserver.HttpServer;

// S24-b: 302 리다이렉트를 따라가지 않는지(Authorization이 다른 서버로 새지 않는지) 가짜 서버 두 대로 본다. 구글은 부르지 않는다.
class FcmPushSenderRedirectTests {

	private static final String ACCESS_TOKEN = "ACCESS-TOKEN-fake-redirect";

	private HttpServer origin;
	private HttpServer other;
	private final List<String> otherAuth = new CopyOnWriteArrayList<>();
	private final List<String> otherPaths = new CopyOnWriteArrayList<>();
	private final AtomicInteger originHits = new AtomicInteger(); // 첫 서버가 받은 요청 수

	@BeforeEach
	void start() throws IOException {
		// 다른 호스트 역할: 요청이 오면 기록만 한다
		other = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		other.createContext("/", ex -> {
			otherAuth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
			otherPaths.add(ex.getRequestURI().toString());
			ex.sendResponseHeaders(200, -1);
			ex.close();
		});
		other.start();
		// 첫 서버: 무조건 다른 서버로 302
		origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		String target = "http://127.0.0.1:" + other.getAddress().getPort() + "/stolen";
		origin.createContext("/", ex -> {
			originHits.incrementAndGet();
			ex.getRequestBody().readAllBytes();
			ex.getResponseHeaders().add("Location", target);
			ex.sendResponseHeaders(302, -1);
			ex.close();
		});
		origin.start();
	}

	@AfterEach
	void stop() {
		origin.stop(0);
		other.stop(0);
	}

	private FcmPushSender sender() {
		return new FcmPushSender("http://127.0.0.1:" + origin.getAddress().getPort(), "demo-test-project", Duration.ofSeconds(5),
				() -> ACCESS_TOKEN);
	}

	@Test
	void 리다이렉트_302는_따라가지_않아_Authorization이_다른_서버로_새지_않는다() throws Exception {
		sender().send("DEVICE-TOKEN-fake", "t", "b");

		assertThat(originHits.get()).as("첫 서버가 요청을 실제로 1번 받았다").isEqualTo(1);
		TimeUnit.MILLISECONDS.sleep(300); // 혹시 따라갔다면 도착할 시간을 준다
		assertThat(otherPaths).isEmpty();
		assertThat(otherAuth).isEmpty();
	}

	// 302는 성공이 아니므로 SENT면 안 된다(알림이 갔다고 잘못 기록됨)
	@Test
	void 리다이렉트_302_응답은_SENT가_아니라_FAILED다() {
		assertThat(sender().send("DEVICE-TOKEN-fake", "t", "b")).isEqualTo(PushResult.FAILED);
	}

}
