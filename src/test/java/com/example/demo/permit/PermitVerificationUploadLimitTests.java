package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.FileSystemUtils;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

// S20-a: 허가서 신청의 멀티파트 한도(파일 10MB, 요청 11MB)를 넘으면 413인지 "진짜 서버 포트"로 확인한다.
// MockMvc는 서버(Tomcat) 앞단을 거치지 않아 한도를 검사하지 않으므로 이 테스트만 RANDOM_PORT를 쓴다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class PermitVerificationUploadLimitTests {

	@LocalServerPort int port;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Value("${app.photo.local-dir}") String uploadDir;

	HttpClient client = HttpClient.newHttpClient();
	String accessToken;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		User 회원 = TestLogin.사용자(userRepository, "permitlimit");
		accessToken = tokenService.issueAccessToken(회원.getId(), 회원.getUsername(), 회원.getRole());
	}

	@AfterEach
	void 정리() throws Exception {
		jdbc.update("delete from permit_requests");
		postRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(Path.of(uploadDir));
	}

	// 실제 HTTP로 신청을 보낸다. CSRF는 /csrf에서 받은 XSRF-TOKEN 쿠키 값을 헤더로 돌려보내는 브라우저와 같은 방식.
	private int 보내기(byte[] 파일바이트) throws Exception {
		String base = "http://localhost:" + port;
		HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/csrf")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		String xsrf = csrf.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN="))
				.map(c -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';'))).findFirst().orElseThrow();

		String boundary = "----permitlimit";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"consent\"\r\n\r\ntrue\r\n")
				.getBytes(StandardCharsets.UTF_8));
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.jpg\"\r\n"
				+ "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(파일바이트);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/users/me/permit-verification"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.header("Cookie", "access_token=" + accessToken + "; XSRF-TOKEN=" + xsrf)
				.header("X-XSRF-TOKEN", xsrf)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
		return client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode();
	}

	private long 창고파일수() throws Exception {
		Path dir = Path.of(uploadDir);
		if (!Files.exists(dir)) {
			return 0;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(Files::isRegularFile).count();
		}
	}

	@Test
	void 파일이_10MB를_조금_넘으면_413이다() throws Exception {
		assertThat(보내기(new byte[10 * 1024 * 1024 + 1])).isEqualTo(413);
		assertThat(창고파일수()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from permit_requests", Long.class)).isZero();
	}

	@Test
	void 요청_전체가_11MB를_넘으면_413이다() throws Exception {
		assertThat(보내기(new byte[15 * 1024 * 1024])).isEqualTo(413);
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 한도_안의_큰_파일은_413이_아니라_사진_검사_400이다() throws Exception {
		// 9MB짜리 가짜 사진: 한도는 넘지 않으므로 413이 아니고, JPEG/PNG가 아니라 400
		assertThat(보내기(new byte[9 * 1024 * 1024])).isEqualTo(400);
	}
}
