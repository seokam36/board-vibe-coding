package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.repository.UserRepository;

// 진짜 서버(실제 포트)에서 Set-Cookie 속성과 401 응답 헤더를 확인한다. MockMvc는 이를 실제처럼 재현하지 못한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class LoginRealServerTests {

	@LocalServerPort
	int port;
	@Autowired
	UserRepository userRepository;

	private final HttpClient client = HttpClient.newHttpClient();

	@AfterEach
	void 정리() {
		userRepository.deleteAll();
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}

	private HttpResponse<String> send(HttpRequest.Builder b) throws Exception {
		return client.send(b.build(), BodyHandlers.ofString());
	}

	private String 쿠키줄(HttpResponse<String> r, String name) {
		return r.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "=")).findFirst()
				.orElse(null);
	}

	private String 쿠키값(HttpResponse<String> r, String name) {
		String line = 쿠키줄(r, name);
		return line == null ? null : line.split(";")[0].substring(name.length() + 1);
	}

	private String csrf토큰() throws Exception {
		return 쿠키값(send(HttpRequest.newBuilder(URI.create(url("/csrf")))), "XSRF-TOKEN");
	}

	// CSRF 쿠키/헤더를 갖춰 POST 한다. extraCookie 가 있으면 함께 보낸다.
	private HttpResponse<String> postJson(String path, String body, String extraCookie) throws Exception {
		String xsrf = csrf토큰();
		String cookie = "XSRF-TOKEN=" + xsrf + (extraCookie == null ? "" : "; " + extraCookie);
		return send(HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", "application/json")
				.header("Cookie", cookie).header("X-XSRF-TOKEN", xsrf).POST(BodyPublishers.ofString(body)));
	}

	private String json(String u, String p) {
		return "{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}";
	}

	@Test
	void 가입_로그인_me_로그아웃_전체_흐름과_쿠키_속성() throws Exception {
		assertThat(postJson("/auth/signup", json("user01", "password123"), null).statusCode()).isEqualTo(201);

		HttpResponse<String> login = postJson("/auth/login", json("user01", "password123"), null);
		assertThat(login.statusCode()).isEqualTo(200);
		String line = 쿠키줄(login, "access_token");
		assertThat(line).isNotNull();
		assertThat(line).contains("HttpOnly", "Path=/", "SameSite=Lax", "Max-Age=900");
		assertThat(쿠키줄(login, "JSESSIONID")).isNull();

		String token = 쿠키값(login, "access_token");
		HttpResponse<String> me = send(HttpRequest.newBuilder(URI.create(url("/auth/me")))
				.header("Cookie", "access_token=" + token));
		assertThat(me.statusCode()).isEqualTo(200);
		assertThat(me.body()).contains("\"username\":\"user01\"");

		HttpResponse<String> logout = postJson("/auth/logout", "", "access_token=" + token);
		assertThat(logout.statusCode()).isEqualTo(204);
		assertThat(쿠키줄(logout, "access_token")).contains("Max-Age=0");
	}

	@Test
	void 쿠키_없는_me는_401이고_Location_헤더가_없다() throws Exception {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(url("/auth/me"))));
		assertThat(r.statusCode()).isEqualTo(401);
		assertThat(r.headers().firstValue("Location")).isEmpty();
	}

	@Test
	void 깨진_토큰_쿠키가_붙어도_공개_경로는_정상이다() throws Exception {
		String broken = "access_token=aaa.bbb.ccc";
		assertThat(send(HttpRequest.newBuilder(URI.create(url("/csrf"))).header("Cookie", broken)).statusCode())
				.isEqualTo(200);
		assertThat(send(HttpRequest.newBuilder(URI.create(url("/posts"))).header("Cookie", broken)).statusCode())
				.isEqualTo(200);
		assertThat(postJson("/auth/signup", json("user01", "password123"), broken).statusCode()).isEqualTo(201);
		assertThat(postJson("/auth/login", json("user01", "password123"), broken).statusCode()).isEqualTo(200);
	}

	@Test
	void 깨진_토큰_쿠키로_me를_호출하면_401() throws Exception {
		HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(url("/auth/me")))
				.header("Cookie", "access_token=aaa.bbb.ccc"));
		assertThat(r.statusCode()).isEqualTo(401);
		assertThat(r.headers().firstValue("Location")).isEmpty();
	}
}
