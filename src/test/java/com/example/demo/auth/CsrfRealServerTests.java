package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.example.demo.TestcontainersConfiguration;

// 진짜 서버(실제 포트)를 띄워 확인한다. MockMvc는 쿠키 헤더·에러 디스패치를 실제처럼 재현하지 못한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class CsrfRealServerTests {

	@LocalServerPort
	int port;

	private final HttpClient client = HttpClient.newHttpClient();

	private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
		return client.send(b.build(), BodyHandlers.ofString());
	}

	private String 쿠키값(HttpResponse<String> r, String name) {
		return r.headers().allValues("Set-Cookie").stream()
				.filter(c -> c.startsWith(name + "="))
				.map(c -> c.split(";")[0].substring(name.length() + 1))
				.findFirst().orElse(null);
	}

	@Test
	void csrf_호출시_XSRF_TOKEN_쿠키가_설정되고_JSESSIONID는_없다() throws Exception {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/csrf")));
		assertThat(r.statusCode()).isEqualTo(200);
		assertThat(쿠키값(r, "XSRF-TOKEN")).isNotBlank();
		assertThat(쿠키값(r, "JSESSIONID")).isNull();
	}

	@Test
	void 쿠키_값을_X_XSRF_TOKEN_헤더로_보내면_CSRF를_통과한다() throws Exception {
		String token = 쿠키값(send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/csrf"))), "XSRF-TOKEN");
		// 아이디 형식을 일부러 틀려서 가입은 안 되게 하고(400), CSRF 통과 여부만 본다 (403이면 CSRF 실패)
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/signup"))
				.header("Content-Type", "application/json")
				.header("Cookie", "XSRF-TOKEN=" + token)
				.header("X-XSRF-TOKEN", token)
				.POST(BodyPublishers.ofString("{\"username\":\"X\",\"password\":\"password123\"}")));
		assertThat(r.statusCode()).isEqualTo(400);
	}

	@Test
	void 글_목록과_헬스체크는_200이고_JSESSIONID가_없다() throws Exception {
		for (String path : List.of("/posts", "/actuator/health")) {
			HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)));
			assertThat(r.statusCode()).as(path).isEqualTo(200);
			assertThat(쿠키값(r, "JSESSIONID")).as(path).isNull();
		}
	}

	@Test
	void CSRF_없는_POST는_실제_서버에서도_403이다() throws Exception {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/signup"))
				.header("Content-Type", "application/json")
				.POST(BodyPublishers.ofString("{\"username\":\"user01\",\"password\":\"password123\"}")));
		assertThat(r.statusCode()).isEqualTo(403);
		assertThat(쿠키값(r, "JSESSIONID")).isNull();
	}
}
