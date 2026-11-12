package com.example.demo.market;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.FileSystemUtils;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.market.service.MarketItemPhotoService;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.StoredPhoto;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

// S13-g6-2 완료 조건 검사: 사진 보기가 스트리밍으로 바뀐 뒤에도 실제 HTTP(진짜 서버 포트, 실제 LocalPhotoStorage)에서
// 본문·Content-Length·헤더가 맞는지, Range/HEAD 요청이 500이 되지 않는지 확인한다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MarketItemPhotoStreamTests {

	@LocalServerPort int port;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired PhotoStorage photoStorage;
	@Autowired MarketItemPhotoService photoService;
	@Value("${app.photo.local-dir}") String uploadDir;

	HttpClient client = HttpClient.newHttpClient();
	long itemId;
	long otherItemId;
	long photoId;
	byte[] 본 = new byte[300_000]; // 스트림 복사가 여러 번 나눠 일어날 만큼 크게
	byte[] 썸 = new byte[7_777];
	String 본키;
	String 썸키;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		new Random(1).nextBytes(본);
		new Random(2).nextBytes(썸);
		User 판매자 = TestLogin.인증된_사용자(userRepository, jdbc, "streamuser", "연세대학교");
		long school = TestLogin.학교(schoolRepository, "연세대학교").getId();
		String sql = "insert into market_items (seller_id, school_id, title, content, trade_type, price, status, created_at) "
				+ "values (?, ?, '제목', '내용', 'SALE', 1000, 'ON_SALE', now()) returning id";
		itemId = jdbc.queryForObject(sql, Long.class, 판매자.getId(), school);
		otherItemId = jdbc.queryForObject(sql, Long.class, 판매자.getId(), school);
		본키 = "market/" + itemId + "/stream-test.png";
		썸키 = "market/" + itemId + "/stream-test_thumb.png";
		photoStorage.put(본키, 본, "image/png");
		photoStorage.put(썸키, 썸, "image/png");
		photoId = jdbc.queryForObject("insert into market_item_photos (market_item_id, storage_key, thumb_key, content_type, "
				+ "width, height, thumb_width, thumb_height, created_at) values (?, ?, ?, 'image/png', 10, 10, 5, 5, now()) "
				+ "returning id", Long.class, itemId, 본키, 썸키);
	}

	@AfterEach
	void 정리() throws Exception {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(Path.of(uploadDir));
	}

	private String url(long item, long photo, String 쿼리) {
		return "http://localhost:" + port + "/market-items/" + item + "/photos/" + photo + (쿼리 == null ? "" : "?" + 쿼리);
	}

	private HttpResponse<byte[]> 보내기(String method, String url, String range) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody());
		if (range != null) {
			b.header("Range", range);
		}
		return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
	}

	// 완료 조건 1, 2, 7: 실제 LocalPhotoStorage 파일과 본문이 같고, Content-Length가 디스크 파일 크기와 같고, 헤더가 그대로
	@Test
	void 실제_창고_파일과_본문이_같고_Content_Length가_파일_크기이며_헤더가_유지된다() throws Exception {
		for (boolean thumb : new boolean[] { false, true }) {
			byte[] 기대 = thumb ? 썸 : 본;
			String 키 = thumb ? 썸키 : 본키;
			HttpResponse<byte[]> r = 보내기("GET", url(itemId, photoId, thumb ? "size=thumb" : null), null);

			assertThat(r.statusCode()).isEqualTo(200);
			assertThat(r.body()).isEqualTo(기대);
			assertThat(r.body()).isEqualTo(Files.readAllBytes(Path.of(uploadDir).resolve(키)));
			assertThat(r.headers().firstValue("Content-Length")).hasValue(String.valueOf(Files.size(Path.of(uploadDir).resolve(키))));
			assertThat(r.headers().firstValue("Content-Type")).hasValue("image/png");
			assertThat(r.headers().allValues("Cache-Control")).containsExactly("public, max-age=31536000, immutable");
			assertThat(r.headers().allValues("X-Content-Type-Options")).containsExactly("nosniff");
		}
	}

	// 완료 조건 3: 404 / 400 (실제 HTTP)
	@Test
	void 없는_사진_다른_글_사진_창고_파일_없음은_404이고_size_big은_400이다() throws Exception {
		assertThat(보내기("GET", url(itemId, photoId + 1000, null), null).statusCode()).isEqualTo(404);
		assertThat(보내기("GET", url(otherItemId, photoId, null), null).statusCode()).isEqualTo(404);
		assertThat(보내기("GET", url(itemId, photoId, "size=big"), null).statusCode()).isEqualTo(400);

		photoStorage.delete(본키);
		assertThat(보내기("GET", url(itemId, photoId, null), null).statusCode()).isEqualTo(404);
		assertThat(보내기("GET", url(itemId, photoId, "size=thumb"), null).statusCode()).isEqualTo(200);
	}

	// 완료 조건 6(a): Range 헤더가 있어도 500이 아니고, 본문이 올바르다 (206 부분이면 요청한 구간, 200이면 전체)
	@Test
	void Range_요청은_500이_아니고_본문이_올바르다() throws Exception {
		// 실제 동작 고정: InputStreamResource 반환은 Range 처리에서 제외되어 항상 200 + 전체 본문, Accept-Ranges 없음
		for (String range : new String[] { "bytes=0-99", "bytes=299000-", "bytes=999999999-" }) {
			HttpResponse<byte[]> r = 보내기("GET", url(itemId, photoId, null), range);

			assertThat(r.statusCode()).as("Range 응답 상태 (" + range + ")").isEqualTo(200);
			assertThat(r.body()).as("본문 (" + range + ")").isEqualTo(본);
			assertThat(r.headers().firstValue("Accept-Ranges")).as("Accept-Ranges (" + range + ")").isEmpty();
		}
	}

	// 완료 조건 6(b): HEAD는 보안 설정이 GET만 공개라 인증 없이는 401이고, 본문이 없다 (500이 아님)
	@Test
	void HEAD_요청은_보안에서_401이다() throws Exception {
		HttpResponse<byte[]> r = 보내기("HEAD", url(itemId, photoId, null), null);

		assertThat(r.statusCode()).isEqualTo(401);
		assertThat(r.body()).isEmpty();
	}

	// 닫힘 기록용 가짜 창고: 진짜 창고에 위임하되, 열어 준 스트림의 close() 호출을 기록한다
	static class 닫힘기록창고 implements PhotoStorage {
		final PhotoStorage 진짜;
		final AtomicBoolean closed = new AtomicBoolean(false);
		final AtomicBoolean opened = new AtomicBoolean(false);

		닫힘기록창고(PhotoStorage 진짜) {
			this.진짜 = 진짜;
		}

		@Override
		public void put(String key, byte[] bytes, String contentType) {
			진짜.put(key, bytes, contentType);
		}

		@Override
		public Optional<byte[]> get(String key) {
			return 진짜.get(key);
		}

		@Override
		public Optional<StoredPhoto> open(String key) {
			return 진짜.open(key).map(s -> {
				opened.set(true);
				InputStream 감싼 = new FilterInputStream(s.stream()) {
					@Override
					public void close() throws IOException {
						closed.set(true); // close 호출 기록
						super.close();
					}
				};
				return new StoredPhoto(감싼, s.length());
			});
		}

		@Override
		public void delete(String key) {
			진짜.delete(key);
		}
	}

	// 권장 4: 응답이 끝나면 창고가 열어 준 스트림이 닫힌다
	@Test
	void 사진_응답이_끝나면_창고_스트림이_닫힌다() throws Exception {
		PhotoStorage 진짜 = (PhotoStorage) ReflectionTestUtils.getField(photoService, "photoStorage");
		닫힘기록창고 창고 = new 닫힘기록창고(진짜);
		ReflectionTestUtils.setField(photoService, "photoStorage", 창고);
		try {
			HttpResponse<byte[]> r = 보내기("GET", url(itemId, photoId, null), null);

			assertThat(r.statusCode()).isEqualTo(200);
			assertThat(r.body()).isEqualTo(본);
			assertThat(창고.opened.get()).isTrue();
			assertThat(창고.closed.get()).as("응답 뒤 스트림 close() 호출 여부").isTrue();
		} finally {
			ReflectionTestUtils.setField(photoService, "photoStorage", 진짜);
		}
	}

	// 권장 4: 실제 파일 핸들이 남지 않는다 (Windows는 열린 파일을 지울 수 없어서 누수가 드러난다)
	@Test
	void 사진_응답_직후_실제_파일을_지울_수_있다() throws Exception {
		HttpResponse<byte[]> r = 보내기("GET", url(itemId, photoId, null), null);
		assertThat(r.statusCode()).isEqualTo(200);

		Path 파일 = Path.of(uploadDir).resolve(본키);
		Files.delete(파일); // 핸들이 안 닫혔으면 Windows에서 예외
		assertThat(Files.exists(파일)).isFalse();
	}
}
