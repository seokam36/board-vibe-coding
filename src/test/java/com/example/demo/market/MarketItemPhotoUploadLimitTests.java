package com.example.demo.market;

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
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

// S13-g2: 멀티파트 한도(파일 10MB, 요청 11MB)를 넘으면 413이 나는지 "진짜 서버 포트"로 확인한다.
// MockMvc는 서버(Tomcat) 앞단을 거치지 않아 멀티파트 한도를 검사하지 않기 때문에 이 테스트만 RANDOM_PORT를 쓴다.
// (한도를 넘긴 요청은 Tomcat이 남은 본문을 버려 주는 크기(max-swallow-size)도 넉넉해야 응답이 끊기지 않고 413으로 온다)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MarketItemPhotoUploadLimitTests {

	@LocalServerPort int port;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Value("${app.photo.local-dir}") String uploadDir;

	HttpClient client = HttpClient.newHttpClient();
	String accessToken;
	long itemId;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "limituser", "연세대학교");
		accessToken = tokenService.issueAccessToken(판매자.getId(), 판매자.getUsername(), 판매자.getRole());
		itemId = jdbc.queryForObject("insert into market_items (seller_id, school_id, title, content, trade_type, price, "
				+ "status, created_at) values (?, ?, '제목', '내용', 'SALE', 1000, 'ON_SALE', now()) returning id", Long.class,
				판매자.getId(), TestLogin.학교(schoolRepository, "연세대학교").getId());
	}

	@AfterEach
	void 정리() throws Exception {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(Path.of(uploadDir));
	}

	// 실제 HTTP로 사진 올리기를 보낸다. CSRF는 /csrf에서 받은 XSRF-TOKEN 쿠키 값을 헤더로 돌려보내는 브라우저와 같은 방식.
	private int 보내기(byte[] 파일바이트) throws Exception {
		return 보내기(itemId, 파일바이트, true);
	}

	// 대상 글과 로그인 여부를 고를 수 있는 버전 (로그인 안 하면 access_token 쿠키를 보내지 않는다)
	private int 보내기(long 대상글, byte[] 파일바이트, boolean 로그인) throws Exception {
		String base = "http://localhost:" + port;
		HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/csrf")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		String xsrf = csrf.headers().allValues("set-cookie").stream().filter(c -> c.startsWith("XSRF-TOKEN="))
				.map(c -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';'))).findFirst().orElseThrow();

		String boundary = "----limitboundary";
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.jpg\"\r\n"
				+ "Content-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(파일바이트);
		body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

		String cookie = (로그인 ? "access_token=" + accessToken + "; " : "") + "XSRF-TOKEN=" + xsrf;
		HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/market-items/" + 대상글 + "/photos"))
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.header("Cookie", cookie)
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
		// 파일 한 개 한도(10MB = 10,485,760바이트)만 살짝 넘긴다. 요청 전체 한도(11MB) 안이라 "파일 한도"가 걸린다
		assertThat(보내기(new byte[10 * 1024 * 1024 + 1])).isEqualTo(413);
		assertThat(창고파일수()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from market_item_photos", Long.class)).isZero();
	}

	@Test
	void 요청_전체가_11MB를_넘으면_413이다() throws Exception {
		// 요청 전체 한도에 걸리는 훨씬 큰 요청. 서버가 응답 대신 연결을 끊어 버리지 않고 413을 돌려줘야 한다
		assertThat(보내기(new byte[15 * 1024 * 1024])).isEqualTo(413);
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 한도_안의_사진은_413이_아니다() throws Exception {
		// 한도 바로 아래(약 9MB)의 내용물은 사진이 아니라서 400이지만, 413이 아니라는 것으로 한도가 기본 1MB가 아님을 확인한다
		assertThat(보내기(new byte[9 * 1024 * 1024])).isEqualTo(400);
	}

	@Test
	void 로그인_안_하고_큰_파일을_보내도_연결이_끊기지_않고_401이다() throws Exception {
		// 컨트롤러에 가기 전에 401로 거절되므로 본문은 읽히지 않는다. 남은 본문(2MB 초과, 10MB 이하)을 서버가 버려 줘야 응답이 온다
		// 주의: 로컬에서 max-swallow-size 설정을 빼도 이 테스트는 통과했다(연결 끊김이 재현되지 않음). 설정을 지키는 테스트는 아니고 동작 확인용
		assertThat(보내기(itemId, new byte[8 * 1024 * 1024], false)).isEqualTo(401);
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 변조된_토큰으로_큰_파일을_보내도_401이다() throws Exception {
		// 변조된 토큰도 로그인 안 한 것과 같다
		String 원래토큰 = accessToken;
		accessToken = 원래토큰 + "x";
		assertThat(보내기(itemId, new byte[8 * 1024 * 1024], true)).isEqualTo(401);
	}

	@Test
	void 남의_글에_3MB를_보내도_연결이_끊기지_않고_403이다() throws Exception {
		// 다른 판매자의 글. 서비스에서 본문을 읽기 전에 403으로 거절된다
		User 다른판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "otherseller", "연세대학교");
		long 남의글 = jdbc.queryForObject("insert into market_items (seller_id, school_id, title, content, trade_type, price, "
				+ "status, created_at) values (?, ?, '남의 글', '내용', 'SALE', 1000, 'ON_SALE', now()) returning id", Long.class,
				다른판매자.getId(), TestLogin.학교(schoolRepository, "연세대학교").getId());
		assertThat(보내기(남의글, new byte[3 * 1024 * 1024], true)).isEqualTo(403);
		assertThat(창고파일수()).isZero();
	}
}
