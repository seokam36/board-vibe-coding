package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.permit.service.PermitTextReader;
import com.example.demo.permit.service.VisionPermitTextReader;
import com.example.demo.user.domain.User;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S20-e2: PERMIT_OCR_MODE=vision으로 앱 전체를 띄우고, 진짜 구글 대신 가짜 서버(JDK HttpServer)가 답하게 해서
// POST /users/me/permit-verification 전체 길(사진 → 판독 → 판정 → 자동 승인/대기)을 본다. 진짜 Vision은 부르지 않는다(VisionLiveTests가 따로).
// 글자·번호는 모두 지어낸 값이다. 제한 시간은 1초로 줄여 "붙드는 서버" 시험이 빨리 끝나게 한다.
@SpringBootTest(properties = { "app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=" + PermitVisionModeTests.KEY,
		"app.permit.ocr.vision.timeout=1s" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PermitVisionModeTests extends AdminPermitTestBase {

	static final String KEY = "TESTKEY-never-real-98765";
	private static final String NUMBER_RAW = "제 2013-0001 호";
	private static final String NUMBER_DIGITS = "20130001";
	private static final String ERROR_TEXT = "ERRMSG-LEAK-CHECK";

	// 가짜 구글 서버. 스프링이 뜨기 전에 포트가 정해져야 해서 static으로 먼저 띄운다. 붙드는 시험이 다른 요청을 막지 않게 여러 스레드로 받는다.
	private static final HttpServer SERVER = startServer();
	private static final List<String> KEY_HEADERS = new CopyOnWriteArrayList<>();
	private static final List<String> MASK_HEADERS = new CopyOnWriteArrayList<>();
	private static final List<String> PATHS = new CopyOnWriteArrayList<>();
	// 시험마다 정하는 "가짜 구글의 답": 0이면 200 + body, 아니면 그 상태 코드의 오류. hold가 true면 답을 안 주고 붙든다.
	private static volatile int status = 0;
	private static volatile String body = "{\"responses\":[{}]}";
	private static volatile boolean hold = false;
	private static volatile CountDownLatch release = new CountDownLatch(1);

	private static HttpServer startServer() {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			s.setExecutor(Executors.newCachedThreadPool(r -> {
				Thread t = new Thread(r, "fake-vision-server");
				t.setDaemon(true);
				return t;
			}));
			s.createContext("/", ex -> {
				try {
					ex.getRequestBody().readAllBytes();
					KEY_HEADERS.add(String.valueOf(ex.getRequestHeaders().getFirst("x-goog-api-key")));
					MASK_HEADERS.add(String.valueOf(ex.getRequestHeaders().getFirst("X-Goog-FieldMask")));
					PATHS.add(ex.getRequestURI().toString());
					if (hold) {
						release.await(30, TimeUnit.SECONDS); // 시험이 끝나면 풀어 준다
					}
					int code = status == 0 ? 200 : status;
					byte[] bytes = (status == 0 ? body : "{\"error\":{\"code\":" + status + ",\"message\":\"" + ERROR_TEXT + "\"}}")
							.getBytes(StandardCharsets.UTF_8);
					ex.getResponseHeaders().add("Content-Type", "application/json");
					ex.sendResponseHeaders(code, bytes.length);
					ex.getResponseBody().write(bytes);
				} catch (IOException | InterruptedException e) {
					// 클라이언트가 먼저 끊은 경우(시간 초과): 조용히 끝낸다
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

	@DynamicPropertySource
	static void fakeGoogle(DynamicPropertyRegistry registry) {
		registry.add("app.permit.ocr.vision.base-url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
	}

	@AfterAll
	static void stopServer() {
		release.countDown();
		SERVER.stop(0);
	}

	@Autowired PermitTextReader 판독기;

	private ListAppender<ILoggingEvent> logs;
	private Logger rootLogger;

	@BeforeEach
	void 준비() {
		KEY_HEADERS.clear();
		MASK_HEADERS.clear();
		PATHS.clear();
		status = 0;
		body = "{\"responses\":[{}]}";
		hold = false;
		release = new CountDownLatch(1);
		// 루트 로거에 붙여서 모든 로그를 모은다
		rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		logs = new ListAppender<>();
		logs.start();
		rootLogger.addAppender(logs);
	}

	@AfterEach
	void 로그에_비밀이_없는지_확인하고_정리() {
		release.countDown();
		rootLogger.detachAppender(logs);
		// 요청 처리 중(각 테스트 시작~끝) 모인 로그에 키·읽은 글자(번호·나라·학교)·가짜 서버의 오류 문구가 없어야 한다(예외 포함).
		// 앱이 뜰 때의 시작 로그는 여기 안 모인다(시작 로그는 PermitOcrConfigTests가 본다).
		for (ILoggingEvent e : List.copyOf(logs.list)) {
			String all = e.getFormattedMessage() + " " + throwableText(e.getThrowableProxy());
			assertThat(all).as(e.getLoggerName()).doesNotContain(KEY).doesNotContain("2013-0001")
					.doesNotContain(NUMBER_DIGITS).doesNotContain("베트남").doesNotContain("연세대학교").doesNotContain(ERROR_TEXT);
		}
	}

	private static String throwableText(IThrowableProxy p) {
		StringBuilder sb = new StringBuilder();
		for (; p != null; p = p.getCause()) {
			sb.append(p.getClassName()).append(' ').append(p.getMessage()).append(' ');
		}
		return sb.toString();
	}

	// 4가지(제목·국적·학교·번호)가 모두 맞는 글자
	private static String 완벽(String 학교) {
		return String.join("\n", "표준입학허가서", "국적(Country of citizenship): 베트남", "학교명(School name): " + 학교,
				"표준입학허가서 번호(Certificate of Admission No.): " + NUMBER_RAW);
	}

	// 구글이 이 글자를 읽어 줬다고 치는 200 응답
	private static String 글자응답(String text) {
		return "{\"responses\":[{\"fullTextAnnotation\":{\"text\":" + new ObjectMapper().writeValueAsString(text) + "}}]}";
	}

	private MockHttpServletResponse 제출(User u) throws Exception {
		return mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
	}

	private Map<String, Object> 최근행(User u) {
		return jdbc.queryForMap("select * from permit_requests where user_id = ? order by id desc limit 1", u.getId());
	}

	@Test
	void vision_모드면_판독기_빈은_Vision이다() {
		assertThat(판독기).isInstanceOf(VisionPermitTextReader.class);
	}

	@Test
	void 네_가지가_맞는_글자를_읽으면_자동_승인된다() throws Exception {
		body = 글자응답(완벽("연세대학교"));
		User u = 회원("vis1");

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("APPROVED");
		Map<String, Object> row = 최근행(u);
		assertThat(row.get("status")).isEqualTo("APPROVED");
		assertThat(row.get("machine_result")).isEqualTo("ALL_PASSED");
		assertThat(row.get("auto_decided")).isEqualTo(true);
		assertThat(row.get("permit_number_hash")).isEqualTo(permitNumberHash.of(NUMBER_DIGITS));
		assertThat(row.get("photo_key")).isNull();
		assertThat(인증학교(u)).isEqualTo(연세id);
		// 구글에는 키가 헤더로만, 응답 줄이기 헤더와 함께 한 번 갔다(주소에 키 없음)
		assertThat(KEY_HEADERS).containsExactly(KEY);
		assertThat(MASK_HEADERS).containsExactly("responses.fullTextAnnotation.text,responses.error.code");
		assertThat(PATHS).containsExactly("/v1/images:annotate");
	}

	@Test
	void 별칭_표에_없는_학교면_SCHOOL_NOT_MATCHED로_대기한다() throws Exception {
		body = 글자응답(완벽("없는나라대학교"));
		User u = 회원("vis2");

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		Map<String, Object> row = 최근행(u);
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("machine_result")).isEqualTo("SCHOOL_NOT_MATCHED");
		assertThat(row.get("photo_key")).isNotNull();
		assertThat(인증학교(u)).isNull();
	}

	@Test
	void 글자가_없다는_응답이면_UNREADABLE로_대기한다() throws Exception {
		body = "{\"responses\":[{}]}";
		User u = 회원("vis3");

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		assertThat(최근행(u).get("machine_result")).isEqualTo("UNREADABLE");
		assertThat(최근행(u).get("photo_key")).isNotNull();
	}

	@Test
	void 구글이_오류를_주거나_200인데_error가_있어도_UNREADABLE로_대기하고_사진은_남는다() throws Exception {
		int[] statuses = { 403, 429, 500 };
		for (int code : statuses) {
			기본정리();
			status = code;
			User u = 회원("vis4-" + code);

			MockHttpServletResponse r = 제출(u);

			assertThat(r.getStatus()).as("HTTP " + code).isEqualTo(201);
			assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
			Map<String, Object> row = 최근행(u);
			assertThat(row.get("machine_result")).as("HTTP " + code).isEqualTo("UNREADABLE");
			assertThat(row.get("photo_key")).isNotNull();
		}
		// 200인데 사진 단위 오류
		기본정리();
		status = 0;
		body = "{\"responses\":[{\"error\":{\"code\":3,\"message\":\"" + ERROR_TEXT + "\"}}]}";
		User u = 회원("vis4-200");
		MockHttpServletResponse r = 제출(u);
		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		assertThat(최근행(u).get("machine_result")).isEqualTo("UNREADABLE");
		assertThat(최근행(u).get("photo_key")).isNotNull();
	}

	@Test
	void 구글이_답을_안_주고_붙들어도_제한_시간_뒤_UNREADABLE로_대기하고_4초_안에_응답한다() throws Exception {
		hold = true;
		User u = 회원("vis5");

		long start = System.nanoTime();
		MockHttpServletResponse r = 제출(u);
		long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		assertThat(최근행(u).get("machine_result")).isEqualTo("UNREADABLE");
		assertThat(최근행(u).get("photo_key")).isNotNull();
		assertThat(elapsedMs).as("요청 시작~응답(ms)").isLessThan(4000);
	}
}
