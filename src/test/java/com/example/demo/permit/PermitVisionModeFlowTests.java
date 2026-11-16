package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.http.Cookie;

// S20-e2 경계: vision 모드에서도 하루 3회 제한·철회·관리자 승인 같은 기존 흐름이 그대로인지 본다.
// 가짜 구글은 항상 "글자 없음"을 답한다(= 모든 신청이 UNREADABLE 대기). 글자·키는 지어낸 값이다.
@SpringBootTest(properties = { "app.permit.ocr.mode=vision", "app.permit.ocr.vision.api-key=FLOWKEY-never-real-1122",
		"app.permit.ocr.vision.timeout=2s" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PermitVisionModeFlowTests extends AdminPermitTestBase {

	private static final List<String> PATHS = new CopyOnWriteArrayList<>();
	private static final HttpServer SERVER = startServer();

	private static HttpServer startServer() {
		try {
			HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			s.createContext("/", ex -> {
				ex.getRequestBody().readAllBytes();
				PATHS.add(ex.getRequestURI().toString());
				byte[] b = "{\"responses\":[{}]}".getBytes(StandardCharsets.UTF_8);
				ex.getResponseHeaders().add("Content-Type", "application/json");
				ex.sendResponseHeaders(200, b.length);
				ex.getResponseBody().write(b);
				ex.close();
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
		SERVER.stop(0);
	}

	@BeforeEach
	void 호출기록_비우기() {
		PATHS.clear();
	}

	private MockHttpServletResponse 제출(User u) throws Exception {
		return mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
	}

	private MockHttpServletResponse 철회(Cookie c) throws Exception {
		return mockMvc.perform(delete(ME_URL).cookie(c).with(csrf())).andReturn().getResponse();
	}

	@Test
	void vision_모드에서도_하루_네_번째_신청은_429이고_구글은_세_번만_불린다() throws Exception {
		User u = 회원("vflow1");
		for (int i = 0; i < 3; i++) {
			assertThat(제출(u).getStatus()).as((i + 1) + "번째").isEqualTo(201);
			assertThat(철회(쿠키(u)).getStatus()).isEqualTo(204);
		}

		assertThat(제출(u).getStatus()).isEqualTo(429);
		assertThat(PATHS).as("429는 사진 검사·판독 전에 끝난다").hasSize(3);
	}

	@Test
	void vision_모드에서_못_읽어_대기한_신청을_철회하면_204이고_행과_사진이_사라진다() throws Exception {
		User u = 회원("vflow2");
		assertThat(제출(u).getStatus()).isEqualTo(201);
		assertThat(파일수()).isEqualTo(1);

		assertThat(철회(쿠키(u)).getStatus()).isEqualTo(204);

		assertThat(jdbc.queryForObject("select count(*) from permit_requests where user_id = ?", Long.class, u.getId())).isZero();
		assertThat(파일수()).isZero();
		assertThat(인증학교(u)).isNull();
	}

	@Test
	void vision_모드에서도_UNREADABLE_대기_신청을_관리자가_승인할_수_있다() throws Exception {
		User u = 회원("vflow3");
		long id = 신청(u);
		assertThat(jdbc.queryForObject("select machine_result from permit_requests where id = ?", String.class, id))
				.isEqualTo("UNREADABLE");

		assertThat(승인(id, 연세id, "20139999").getStatus()).isEqualTo(200);

		assertThat(인증학교(u)).isEqualTo(연세id);
		assertThat(jdbc.queryForObject("select auto_decided from permit_requests where id = ?", Boolean.class, id)).isFalse();
	}

	@Test
	void vision_모드에서_이미_대기_중이면_두_번째_신청은_409이고_구글을_부르지_않는다() throws Exception {
		User u = 회원("vflow4");
		assertThat(제출(u).getStatus()).isEqualTo(201);
		int calls = PATHS.size();

		assertThat(제출(u).getStatus()).isEqualTo(409);
		assertThat(PATHS).hasSize(calls);
	}
}
