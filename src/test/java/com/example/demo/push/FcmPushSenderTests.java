package com.example.demo.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.example.demo.push.service.AccessTokenProvider;
import com.example.demo.push.service.FcmPushSender;
import com.example.demo.push.service.PushResult;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S24-b: 가짜 구글 서버(JDK HttpServer, PermitVisionModeTests와 같은 방식)로 FcmPushSender의 요청 모양과 결과 분류를 본다.
// 액세스 토큰은 가짜 공급자가 주므로 진짜 구글 토큰 서버·FCM은 절대 부르지 않는다. 기기 토큰·액세스 토큰은 지어낸 값이다.
class FcmPushSenderTests {

	private static final String DEVICE_TOKEN = "DEVICE-TOKEN-fake-abc123";
	private static final String ACCESS_TOKEN = "ACCESS-TOKEN-fake-xyz789";
	private static final String PROJECT = "demo-test-project";

	private static final HttpServer SERVER = startServer();
	private static final List<String> PATHS = new CopyOnWriteArrayList<>();
	private static final List<String> AUTH_HEADERS = new CopyOnWriteArrayList<>();
	private static final List<String> BODIES = new CopyOnWriteArrayList<>();
	// 시험마다 정하는 "가짜 구글의 답"
	private static volatile int status = 200;
	private static volatile String responseBody = "{\"name\":\"projects/x/messages/1\"}";
	private static volatile boolean hold = false;
	private static volatile CountDownLatch release = new CountDownLatch(1);

	private static HttpServer startServer() {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			s.setExecutor(Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "fake-fcm-server");
				t.setDaemon(true);
				return t;
			}));
			s.createContext("/", ex -> {
				try {
					BODIES.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
					AUTH_HEADERS.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
					PATHS.add(ex.getRequestURI().toString());
					if (hold) {
						release.await(30, TimeUnit.SECONDS); // 시험이 끝나면 풀어 준다
					}
					byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
					ex.getResponseHeaders().add("Content-Type", "application/json");
					ex.sendResponseHeaders(status, bytes.length);
					ex.getResponseBody().write(bytes);
				} catch (Exception ignored) {
					// 클라이언트가 먼저 끊은 경우
				} finally {
					ex.close();
				}
			});
			s.start();
			return s;
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@AfterAll
	static void stopServer() {
		release.countDown();
		SERVER.stop(0);
	}

	private final ObjectMapper mapper = new ObjectMapper();
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
	private Logger senderLogger;

	@BeforeEach
	void reset() {
		PATHS.clear();
		AUTH_HEADERS.clear();
		BODIES.clear();
		status = 200;
		responseBody = "{\"name\":\"projects/x/messages/1\"}";
		hold = false;
		release = new CountDownLatch(1);
		senderLogger = (Logger) LoggerFactory.getLogger(FcmPushSender.class);
		logs.list.clear();
		logs.start();
		senderLogger.addAppender(logs);
	}

	@AfterEach
	void cleanup() {
		release.countDown();
		senderLogger.detachAppender(logs);
	}

	private FcmPushSender sender(Duration timeout, AccessTokenProvider provider) {
		return new FcmPushSender("http://127.0.0.1:" + SERVER.getAddress().getPort(), PROJECT, timeout, provider);
	}

	private FcmPushSender sender() {
		return sender(Duration.ofSeconds(5), () -> ACCESS_TOKEN);
	}

	// 로그 전체(메시지 + 예외)에 기기 토큰·액세스 토큰이 없다
	private void assertNoSecretsInLogs() {
		for (ILoggingEvent e : logs.list) {
			String text = e.getFormattedMessage() + e.getThrowableProxy();
			assertThat(text).doesNotContain(DEVICE_TOKEN).doesNotContain(ACCESS_TOKEN).doesNotContain("Bearer");
		}
	}

	@Test
	void 켜져_있다고_답한다() {
		assertThat(sender().enabled()).isTrue();
	}

	@Test
	void 성공_200이면_SENT이고_경로_헤더_본문이_맞다() {
		PushResult result = sender().send(DEVICE_TOKEN, "비자 일정 알림", "앱에서 확인해 주세요.");

		assertThat(result).isEqualTo(PushResult.SENT);
		assertThat(PATHS).containsExactly("/v1/projects/" + PROJECT + "/messages:send");
		assertThat(AUTH_HEADERS).containsExactly("Bearer " + ACCESS_TOKEN);
		JsonNode message = mapper.readTree(BODIES.get(0)).path("message");
		assertThat(message.path("token").asString()).isEqualTo(DEVICE_TOKEN);
		assertThat(message.path("notification").path("title").asString()).isEqualTo("비자 일정 알림");
		assertThat(message.path("notification").path("body").asString()).isEqualTo("앱에서 확인해 주세요.");
		assertNoSecretsInLogs();
	}

	// 값이 null·빈 값이면 예외 없이 FAILED, 구글에는 아무것도 안 보낸다
	@Test
	void token_title_body가_null이거나_비면_예외_없이_FAILED다() {
		FcmPushSender s = sender();
		assertThat(s.send(null, "t", "b")).isEqualTo(PushResult.FAILED);
		assertThat(s.send(DEVICE_TOKEN, null, "b")).isEqualTo(PushResult.FAILED);
		assertThat(s.send(DEVICE_TOKEN, "t", null)).isEqualTo(PushResult.FAILED);
		assertThat(s.send("", "t", "b")).isEqualTo(PushResult.FAILED);
		assertThat(PATHS).isEmpty();
		assertNoSecretsInLogs();
	}

	@Test
	void 주소_끝에_슬래시가_있어도_경로가_맞다() {
		FcmPushSender s = new FcmPushSender("http://127.0.0.1:" + SERVER.getAddress().getPort() + "/", PROJECT, Duration.ofSeconds(5),
				() -> ACCESS_TOKEN);
		assertThat(s.send(DEVICE_TOKEN, "t", "b")).isEqualTo(PushResult.SENT);
		assertThat(PATHS).containsExactly("/v1/projects/" + PROJECT + "/messages:send");
	}

	@Test
	void 아주_긴_글과_따옴표_줄바꿈이_들어가도_본문이_올바른_JSON이다() {
		String title = "제목 \"따옴표\" \\ 역슬래시";
		String body = "첫 줄\n둘째 줄\t탭";
		assertThat(sender().send(DEVICE_TOKEN, title, body)).isEqualTo(PushResult.SENT);
		JsonNode n = mapper.readTree(BODIES.get(0)).path("message").path("notification");
		assertThat(n.path("title").asString()).isEqualTo(title);
		assertThat(n.path("body").asString()).isEqualTo(body);
	}

	@Test
	void 토큰이_죽었으면_404_UNREGISTERED가_TOKEN_INVALID다() {
		status = 404;
		responseBody = "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\",\"details\":[{\"errorCode\":\"UNREGISTERED\"}]}}";

		assertThat(sender().send(DEVICE_TOKEN, "t", "b")).isEqualTo(PushResult.TOKEN_INVALID);
		assertNoSecretsInLogs();
	}

	// UNREGISTERED가 없는 404는 프로젝트 ID 실수 같은 설정 문제일 수 있어 토큰을 지우라고 하지 않는다
	@Test
	void UNREGISTERED_없는_404는_REJECTED다() {
		status = 404;
		responseBody = "{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}";

		assertThat(sender().send(DEVICE_TOKEN, "t", "b")).isEqualTo(PushResult.REJECTED);
		assertNoSecretsInLogs();
	}

	// 4xx(429 제외)는 구글이 답한 거절(REJECTED), 429·5xx는 구글이 제대로 못 답한 것(FAILED)
	@Test
	void 오류_상태_코드는_4xx는_REJECTED_429와_5xx는_FAILED이고_예외가_안_난다() {
		for (int code : new int[] { 400, 401, 403, 429, 500, 503 }) {
			status = code;
			responseBody = "{\"error\":{\"code\":" + code + ",\"message\":\"" + DEVICE_TOKEN + " " + ACCESS_TOKEN + "\"}}";
			PushResult expected = (code == 429 || code >= 500) ? PushResult.FAILED : PushResult.REJECTED;
			assertThat(sender().send(DEVICE_TOKEN, "t", "b")).as("HTTP " + code).isEqualTo(expected);
		}
		// 로그에는 상태 숫자만 있고, 응답 본문에 섞여 온 토큰은 없다
		assertThat(logs.list).hasSize(6);
		assertThat(logs.list.get(0).getFormattedMessage()).contains("400");
		assertNoSecretsInLogs();
	}

	@Test
	void 응답이_늦으면_시간_초과로_FAILED이고_예외가_안_난다() {
		hold = true;
		long start = System.nanoTime();

		PushResult result = sender(Duration.ofSeconds(1), () -> ACCESS_TOKEN).send(DEVICE_TOKEN, "t", "b");

		assertThat(result).isEqualTo(PushResult.FAILED);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
		assertThat(logs.list.get(0).getFormattedMessage()).contains("시간 초과");
		assertNoSecretsInLogs();
	}

	@Test
	void 서버에_연결할_수_없으면_FAILED이고_예외가_안_난다() {
		FcmPushSender s = new FcmPushSender("http://127.0.0.1:1", PROJECT, Duration.ofSeconds(2), () -> ACCESS_TOKEN);

		assertThat(s.send(DEVICE_TOKEN, "t", "b")).isEqualTo(PushResult.FAILED);
		assertNoSecretsInLogs();
	}

	@Test
	void 액세스_토큰을_못_받으면_FAILED이고_구글에_요청하지_않는다() {
		FcmPushSender s = sender(Duration.ofSeconds(5), () -> {
			throw new IOException("token server down " + ACCESS_TOKEN);
		});

		assertThat(s.send(DEVICE_TOKEN, "t", "b")).isEqualTo(PushResult.FAILED);
		assertThat(PATHS).isEmpty();
		assertNoSecretsInLogs();
	}

	@Test
	void 액세스_토큰_공급자가_런타임_예외를_내도_FAILED다() {
		FcmPushSender s = sender(Duration.ofSeconds(5), () -> {
			throw new IllegalStateException("boom " + ACCESS_TOKEN);
		});

		assertThat(s.send(DEVICE_TOKEN, "t", "b")).isEqualTo(PushResult.FAILED);
		assertNoSecretsInLogs();
	}
}
