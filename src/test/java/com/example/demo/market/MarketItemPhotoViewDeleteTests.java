package com.example.demo.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.market.service.MarketItemPhotoService;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.PhotoStorageException;
import com.example.demo.photo.service.PhotoDeleteQueue;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S13-g3 완료 조건 검사: 사진 보기(GET /market-items/{id}/photos/{photoId})와 사진 지우기(DELETE 같은 주소).
// MarketItemPhotoApiTests와 설정(@SpringBootTest·@Import·@AutoConfigureMockMvc)이 같아 Spring 컨텍스트를 같이 쓴다(새로 만들지 않음).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemPhotoViewDeleteTests {

	static final String CACHE_CONTROL = "public, max-age=31536000, immutable";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired PhotoStorage photoStorage;
	@Autowired MarketItemPhotoService photoService;
	@Autowired PhotoDeleteQueue photoDeleteQueue;
	@Autowired PlatformTransactionManager txManager;
	@Value("${app.photo.local-dir}") String uploadDir;

	User 회원;
	User 남;
	User 관리자;
	Cookie 쿠키;
	Cookie 남쿠키;
	Cookie 관리자쿠키;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "vuser", "연세대학교");
		남 = TestLogin.인증된_사용자(userRepository, jdbc, "vother", "고려대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "vadmin");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		남쿠키 = TestLogin.쿠키(tokenService, 남);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
	}

	@AfterEach
	void 정리() throws Exception {
		jdbc.update("delete from market_items"); // 사진 행은 CASCADE로 같이 지워진다
		jdbc.update("delete from photo_delete_queue");
		postRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(Path.of(uploadDir));
	}

	// ---------- 도우미 ----------

	private long 글올리기(Cookie c) throws Exception {
		String json = "{\"title\":\"제목\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(json).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private static byte[] 그림(String 형식, int w, int h) throws Exception {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, w, h);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, 형식, out);
		return out.toByteArray();
	}

	// 사진 한 장을 실제 업로드 API로 올리고 사진 id를 돌려준다 (준비용)
	private long 사진올리기(long itemId, String 형식, int w, int h) throws Exception {
		String 종류 = "png".equals(형식) ? "image/png" : "image/jpeg";
		MockHttpServletResponse r = mockMvc.perform(multipart("/market-items/" + itemId + "/photos")
				.file(new MockMultipartFile("file", "a." + 형식, 종류, 그림(형식, w, h))).cookie(쿠키).with(csrf()))
				.andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 사진 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private long 사진올리기(long itemId) throws Exception {
		return 사진올리기(itemId, "jpg", 1200, 800);
	}

	private MockHttpServletResponse 보기(long itemId, long photoId, String 쿼리, Cookie c) throws Exception {
		var b = get("/market-items/" + itemId + "/photos/" + photoId + (쿼리 == null ? "" : "?" + 쿼리));
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private MockHttpServletResponse 지우기(long itemId, long photoId, Cookie c, boolean csrf토큰) throws Exception {
		var b = delete("/market-items/" + itemId + "/photos/" + photoId);
		if (c != null) {
			b.cookie(c);
		}
		if (csrf토큰) {
			b.with(csrf());
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private MockHttpServletResponse 지우기(long itemId, long photoId, Cookie c) throws Exception {
		return 지우기(itemId, photoId, c, true);
	}

	private long 사진수(long itemId) {
		return jdbc.queryForObject("select count(*) from market_item_photos where market_item_id = ?", Long.class, itemId);
	}

	private String 키(long photoId, String 칸) {
		return jdbc.queryForObject("select " + 칸 + " from market_item_photos where id = ?", String.class, photoId);
	}

	private static BufferedImage 읽기(byte[] 바이트) throws Exception {
		return ImageIO.read(new ByteArrayInputStream(바이트));
	}

	private void 거래완료로(long itemId) {
		jdbc.update("update market_items set status = 'DONE' where id = ?", itemId);
	}

	// ============ 사진 보기 (GET) ============

	@Test
	void JPEG_본_사진은_창고_파일_바이트와_같고_크기가_DB와_같다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id, "jpg", 1200, 800);

		MockHttpServletResponse r = 보기(id, photoId, null, null);

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentType()).isEqualTo("image/jpeg");
		byte[] 창고 = photoStorage.get(키(photoId, "storage_key")).orElseThrow();
		assertThat(r.getContentAsByteArray()).isEqualTo(창고);
		BufferedImage img = 읽기(r.getContentAsByteArray());
		assertThat(img.getWidth()).isEqualTo(jdbc.queryForObject("select width from market_item_photos where id = ?", Integer.class, photoId));
		assertThat(img.getHeight()).isEqualTo(jdbc.queryForObject("select height from market_item_photos where id = ?", Integer.class, photoId));
		assertThat(img.getWidth()).isEqualTo(1200);
		assertThat(img.getHeight()).isEqualTo(800);
	}

	@Test
	void size가_thumb이면_썸네일을_돌려준다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id, "jpg", 1200, 800);

		MockHttpServletResponse r = 보기(id, photoId, "size=thumb", null);

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentType()).isEqualTo("image/jpeg");
		assertThat(r.getContentAsByteArray()).isEqualTo(photoStorage.get(키(photoId, "thumb_key")).orElseThrow());
		BufferedImage img = 읽기(r.getContentAsByteArray());
		assertThat(img.getWidth()).isEqualTo(jdbc.queryForObject("select thumb_width from market_item_photos where id = ?", Integer.class, photoId));
		assertThat(img.getHeight()).isEqualTo(jdbc.queryForObject("select thumb_height from market_item_photos where id = ?", Integer.class, photoId));
		assertThat(img.getWidth()).isEqualTo(320);
		assertThat(img.getHeight()).isEqualTo(213);
		// 본 사진과 다른 파일이다
		assertThat(r.getContentAsByteArray()).isNotEqualTo(보기(id, photoId, null, null).getContentAsByteArray());
	}

	@Test
	void PNG는_Content_Type이_image_png다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id, "png", 600, 400);

		MockHttpServletResponse 본 = 보기(id, photoId, null, null);
		MockHttpServletResponse 썸네일 = 보기(id, photoId, "size=thumb", null);

		assertThat(본.getStatus()).isEqualTo(200);
		assertThat(본.getContentType()).isEqualTo("image/png");
		assertThat(썸네일.getContentType()).isEqualTo("image/png");
		assertThat(읽기(본.getContentAsByteArray()).getWidth()).isEqualTo(600);
		assertThat(읽기(썸네일.getContentAsByteArray()).getWidth()).isEqualTo(320);
	}

	@Test
	void 응답_헤더는_Spring_Security_기본값에_덮이지_않고_캐시_1년_immutable과_nosniff와_Content_Length가_실제로_나간다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		for (String 쿼리 : new String[] { null, "size=thumb" }) {
			MockHttpServletResponse r = 보기(id, photoId, 쿼리, null);

			assertThat(r.getStatus()).isEqualTo(200);
			// 헤더가 딱 한 번, 정확히 이 값으로 나간다 (Security의 "no-cache, no-store..."가 섞이거나 덮으면 실패)
			assertThat(r.getHeaders("Cache-Control")).containsExactly(CACHE_CONTROL);
			assertThat(r.getHeaders("X-Content-Type-Options")).containsExactly("nosniff");
			assertThat(r.getHeader("Content-Length")).isEqualTo(String.valueOf(r.getContentAsByteArray().length));
			// 캐시를 막는 옛 헤더가 같이 붙지 않는다
			assertThat(r.getHeader("Pragma")).isNull();
			assertThat(r.getHeader("Expires")).isNull();
		}
	}

	@Test
	void 로그인하지_않아도_200이고_만료_변조된_토큰_쿠키가_붙어도_200이다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		assertThat(보기(id, photoId, null, null).getStatus()).isEqualTo(200);
		assertThat(보기(id, photoId, null, new Cookie("access_token", "변조된.토큰.값")).getStatus()).isEqualTo(200);
		assertThat(보기(id, photoId, "size=thumb", new Cookie("access_token", "abc")).getStatus()).isEqualTo(200);
		// 다른 회원이 로그인해 있어도 마찬가지
		assertThat(보기(id, photoId, null, 남쿠키).getStatus()).isEqualTo(200);
	}

	@Test
	void size가_이상한_값이면_400이다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		for (String 쿼리 : new String[] { "size=big", "size=", "size=THUMB", "size=original", "size=thumb%20" }) {
			assertThat(보기(id, photoId, 쿼리, null).getStatus()).as(쿼리).isEqualTo(400);
		}
	}

	@Test
	void 없는_사진이면_404고_없는_글이어도_404다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		assertThat(보기(id, photoId + 1000, null, null).getStatus()).isEqualTo(404);
		assertThat(보기(id + 1000, photoId, null, null).getStatus()).isEqualTo(404);
	}

	@Test
	void 사진_번호가_숫자가_아니면_400이다() throws Exception {
		long id = 글올리기(쿠키);
		assertThat(mockMvc.perform(get("/market-items/" + id + "/photos/abc")).andReturn().getResponse().getStatus())
				.isEqualTo(400);
	}

	@Test
	void 다른_글_번호로_요청하면_404고_사진은_보이지_않는다() throws Exception {
		long 내글 = 글올리기(쿠키);
		long 남글 = 글올리기(남쿠키);
		long photoId = 사진올리기(내글);

		assertThat(보기(내글, photoId, null, null).getStatus()).isEqualTo(200);
		assertThat(보기(남글, photoId, null, null).getStatus()).isEqualTo(404);
		assertThat(보기(남글, photoId, "size=thumb", null).getStatus()).isEqualTo(404);
	}

	@Test
	void 장부에는_있는데_창고_파일이_없으면_500이_아니라_404다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);
		photoStorage.delete(키(photoId, "storage_key"));

		assertThat(보기(id, photoId, null, null).getStatus()).isEqualTo(404);
		// 썸네일 파일은 남아 있으니 썸네일은 그대로 보인다 (파일마다 따로 판단)
		assertThat(보기(id, photoId, "size=thumb", null).getStatus()).isEqualTo(200);

		photoStorage.delete(키(photoId, "thumb_key"));
		assertThat(보기(id, photoId, "size=thumb", null).getStatus()).isEqualTo(404);
	}

	// ============ 사진 지우기 (DELETE) ============

	@Test
	void 본인이_지우면_204이고_행과_창고_파일_2개가_사라지고_이후_보기는_404며_단건_photos에서_빠진다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);
		String 본키 = 키(photoId, "storage_key");
		String 썸키 = 키(photoId, "thumb_key");
		assertThat(photoStorage.get(본키)).isPresent();
		assertThat(photoStorage.get(썸키)).isPresent();

		MockHttpServletResponse r = 지우기(id, photoId, 쿠키);

		assertThat(r.getStatus()).isEqualTo(204);
		assertThat(r.getContentAsByteArray()).isEmpty();
		assertThat(사진수(id)).isZero();
		assertThat(photoStorage.get(본키)).isEmpty();
		assertThat(photoStorage.get(썸키)).isEmpty();
		assertThat(보기(id, photoId, null, null).getStatus()).isEqualTo(404);
		assertThat(보기(id, photoId, "size=thumb", null).getStatus()).isEqualTo(404);
		JsonNode 단건 = om.readTree(mockMvc.perform(get("/market-items/" + id)).andReturn().getResponse().getContentAsString());
		assertThat(단건.get("photos")).isEmpty();
	}

	@Test
	void 첫_장을_지우면_단건_photos에서_다음_장이_첫_번째다() throws Exception {
		long id = 글올리기(쿠키);
		long 첫째 = 사진올리기(id);
		long 둘째 = 사진올리기(id);
		long 셋째 = 사진올리기(id);

		assertThat(지우기(id, 첫째, 쿠키).getStatus()).isEqualTo(204);

		JsonNode photos = om.readTree(mockMvc.perform(get("/market-items/" + id)).andReturn().getResponse()
				.getContentAsString()).get("photos");
		assertThat(photos).hasSize(2);
		assertThat(photos.get(0).get("id").asLong()).isEqualTo(둘째);
		assertThat(photos.get(1).get("id").asLong()).isEqualTo(셋째);
		// 지우지 않은 사진 파일은 그대로 보인다
		assertThat(보기(id, 둘째, null, null).getStatus()).isEqualTo(200);
	}

	@Test
	void 로그인하지_않았으면_401이고_아무것도_지워지지_않는다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		assertThat(지우기(id, photoId, null).getStatus()).isEqualTo(401);

		assertThat(사진수(id)).isEqualTo(1);
		assertThat(photoStorage.get(키(photoId, "storage_key"))).isPresent();
	}

	@Test
	void CSRF_토큰이_없으면_403이고_로그인_검사보다_먼저다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		assertThat(지우기(id, photoId, 쿠키, false).getStatus()).isEqualTo(403);
		assertThat(지우기(id, photoId, null, false).getStatus()).as("비로그인이어도 CSRF가 먼저").isEqualTo(403);

		assertThat(사진수(id)).isEqualTo(1);
	}

	@Test
	void 남의_글이면_403이고_관리자도_403이며_아무것도_지워지지_않는다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		assertThat(지우기(id, photoId, 남쿠키).getStatus()).isEqualTo(403);
		assertThat(지우기(id, photoId, 관리자쿠키).getStatus()).isEqualTo(403);

		assertThat(사진수(id)).isEqualTo(1);
		assertThat(photoStorage.get(키(photoId, "storage_key"))).isPresent();
		assertThat(photoStorage.get(키(photoId, "thumb_key"))).isPresent();
	}

	@Test
	void 거래완료_글의_사진은_409이고_지워지지_않는다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);
		거래완료로(id);

		assertThat(지우기(id, photoId, 쿠키).getStatus()).isEqualTo(409);

		assertThat(사진수(id)).isEqualTo(1);
		assertThat(photoStorage.get(키(photoId, "storage_key"))).isPresent();
		assertThat(photoStorage.get(키(photoId, "thumb_key"))).isPresent();
		assertThat(보기(id, photoId, null, null).getStatus()).isEqualTo(200);
	}

	@Test
	void 검사_순서는_글_404_다음_본인_403_다음_거래완료_409_다음_사진_404다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		// 없는 글은 404 (사진 번호가 맞아도)
		assertThat(지우기(id + 1000, photoId, 쿠키).getStatus()).isEqualTo(404);
		// 남의 글 + 없는 사진 번호: 사진 404보다 본인 403이 먼저
		assertThat(지우기(id, photoId + 1000, 남쿠키).getStatus()).isEqualTo(403);
		// 남의 거래완료 글: 409보다 403이 먼저
		거래완료로(id);
		assertThat(지우기(id, photoId, 남쿠키).getStatus()).isEqualTo(403);
		// 본인 거래완료 글 + 없는 사진 번호: 사진 404보다 409가 먼저
		assertThat(지우기(id, photoId + 1000, 쿠키).getStatus()).isEqualTo(409);
	}

	@Test
	void 다른_글의_사진_번호로_지우려면_404고_그_사진은_그대로다() throws Exception {
		long 내글 = 글올리기(쿠키);
		long 다른내글 = 글올리기(쿠키);
		long photoId = 사진올리기(내글);

		assertThat(지우기(다른내글, photoId, 쿠키).getStatus()).isEqualTo(404);

		assertThat(사진수(내글)).isEqualTo(1);
		assertThat(photoStorage.get(키(photoId, "storage_key"))).isPresent();
		assertThat(보기(내글, photoId, null, null).getStatus()).isEqualTo(200);
	}

	@Test
	void 이미_지운_사진을_또_지우면_404다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		assertThat(지우기(id, photoId, 쿠키).getStatus()).isEqualTo(204);
		assertThat(지우기(id, photoId, 쿠키).getStatus()).isEqualTo(404);
	}

	@Test
	void 창고_지우기가_실패해도_204이고_행은_삭제되며_두_번째_파일도_지우기를_시도한다() throws Exception {
		// 무엇/왜: delete가 항상 PhotoStorageException을 던지는 가짜 창고로 바꿔 끼워, "커밋 뒤 파일 지우기 실패는 응답에 영향이 없다"를 본다.
		// (컨텍스트를 늘리지 않으려고 서비스의 final 필드를 잠깐 바꿨다가 되돌린다. g2 테스트와 같은 방식)
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);
		String 본키 = 키(photoId, "storage_key");
		String 썸키 = 키(photoId, "thumb_key");
		// (S13-g4부터 파일 지우기는 PhotoDeleteQueue를 거치므로 바꿔 끼우는 곳도 거기다)
		PhotoStorage 진짜 = (PhotoStorage) ReflectionTestUtils.getField(photoDeleteQueue, "photoStorage");
		List<String> 시도한키 = new ArrayList<>();
		PhotoStorage 안지워지는창고 = new PhotoStorage() {
			@Override
			public void put(String key, byte[] bytes, String contentType) {
				진짜.put(key, bytes, contentType);
			}

			@Override
			public Optional<byte[]> get(String key) {
				return 진짜.get(key);
			}

			@Override
			public void delete(String key) {
				시도한키.add(key);
				throw new PhotoStorageException("일부러 실패", new RuntimeException("디스크 오류"));
			}
		};
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 안지워지는창고);
		try {
			assertThat(지우기(id, photoId, 쿠키).getStatus()).isEqualTo(204);
		} finally {
			ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 진짜);
		}

		assertThat(사진수(id)).as("장부 행은 이미 삭제됨").isZero();
		assertThat(시도한키).containsExactlyInAnyOrder(본키, 썸키);
		// 파일은 고아로 남았지만 화면에서는 사라진 사진이다
		assertThat(보기(id, photoId, null, null).getStatus()).isEqualTo(404);
	}

	// ============ 보충: 이어지는 흐름·경계 ============

	@Test
	void 업로드_응답의_Location_주소로_GET하면_실제로_200이다() throws Exception {
		// g2의 Location과 g3의 GET 주소가 이어지는지
		long id = 글올리기(쿠키);
		MockHttpServletResponse 올림 = mockMvc.perform(multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림("jpg", 800, 600))).cookie(쿠키).with(csrf()))
				.andReturn().getResponse();
		assertThat(올림.getStatus()).isEqualTo(201);

		MockHttpServletResponse r = mockMvc.perform(get(올림.getHeader("Location"))).andReturn().getResponse();

		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentType()).isEqualTo("image/jpeg");
		assertThat(읽기(r.getContentAsByteArray()).getWidth()).isEqualTo(800);
	}

	@Test
	void 열_장을_채운_뒤_한_장_지우면_다시_한_장_올릴_수_있다() throws Exception {
		long id = 글올리기(쿠키);
		long 첫째 = 사진올리기(id, "jpg", 100, 100);
		for (int i = 1; i < 10; i++) {
			사진올리기(id, "jpg", 100, 100);
		}
		assertThat(사진수(id)).isEqualTo(10);
		// 11번째는 막힌다
		assertThat(mockMvc.perform(multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림("jpg", 100, 100))).cookie(쿠키).with(csrf()))
				.andReturn().getResponse().getStatus()).isEqualTo(409);

		assertThat(지우기(id, 첫째, 쿠키).getStatus()).isEqualTo(204);

		assertThat(사진수(id)).isEqualTo(9);
		사진올리기(id, "jpg", 100, 100); // 201이 아니면 준비 단언에서 실패한다
		assertThat(사진수(id)).isEqualTo(10);
	}

	@Test
	void 글을_지우면_그_글의_사진_GET은_404다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);
		assertThat(보기(id, photoId, null, null).getStatus()).isEqualTo(200);

		assertThat(mockMvc.perform(delete("/market-items/" + id).cookie(쿠키).with(csrf())).andReturn().getResponse()
				.getStatus()).isEqualTo(204);

		assertThat(보기(id, photoId, null, null).getStatus()).isEqualTo(404);
		assertThat(보기(id, photoId, "size=thumb", null).getStatus()).isEqualTo(404);
	}

	@Test
	void 알파가_있는_PNG는_본_사진과_썸네일_모두_투명이_유지된다() throws Exception {
		long id = 글올리기(쿠키);
		// 왼쪽 위 모서리가 완전 투명인 PNG
		BufferedImage 원본 = new BufferedImage(600, 400, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = 원본.createGraphics();
		g.setColor(Color.RED);
		g.fillRect(0, 0, 600, 400);
		g.setComposite(java.awt.AlphaComposite.Clear);
		g.fillRect(0, 0, 300, 200);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(원본, "png", out);
		MockHttpServletResponse 올림 = mockMvc.perform(multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("file", "a.png", "image/png", out.toByteArray())).cookie(쿠키).with(csrf()))
				.andReturn().getResponse();
		assertThat(올림.getStatus()).isEqualTo(201);
		long photoId = om.readTree(올림.getContentAsString()).get("id").asLong();

		for (String 쿼리 : new String[] { null, "size=thumb" }) {
			BufferedImage img = 읽기(보기(id, photoId, 쿼리, null).getContentAsByteArray());
			assertThat(img.getColorModel().hasAlpha()).as("알파 채널 " + 쿼리).isTrue();
			int 투명 = (img.getRGB(2, 2) >>> 24) & 0xff;
			int 불투명 = (img.getRGB(img.getWidth() - 3, img.getHeight() - 3) >>> 24) & 0xff;
			assertThat(투명).as("투명 영역 " + 쿼리).isZero();
			assertThat(불투명).as("불투명 영역 " + 쿼리).isEqualTo(255);
		}
	}

	@Test
	void 거래완료_글의_사진_보기는_막지_않아_200이다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);
		거래완료로(id);

		assertThat(보기(id, photoId, null, null).getStatus()).isEqualTo(200);
		assertThat(보기(id, photoId, "size=thumb", null).getStatus()).isEqualTo(200);
	}

	@Test
	void 같은_사진을_두_요청이_동시에_지우면_하나는_204_하나는_404고_창고_파일은_지워진다() throws Exception {
		// 방법: 테스트가 글 행을 먼저 잠가 둔다 -> 두 DELETE가 모두 잠금에서 대기 -> 잠금을 풀면 차례로 들어가 먼저 온 쪽이 지우고 뒤쪽은 사진이 없어 404
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);
		String 본키 = 키(photoId, "storage_key");
		String 썸키 = 키(photoId, "thumb_key");
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			@SuppressWarnings("unchecked")
			Future<Integer>[] futures = new Future[2];
			new TransactionTemplate(txManager).executeWithoutResult(s -> {
				jdbc.queryForList("select id from market_items where id = ? for update", id);
				for (int i = 0; i < 2; i++) {
					futures[i] = pool.submit(() -> 지우기(id, photoId, 쿠키).getStatus());
				}
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 2);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertThat(futures[0].isDone() || futures[1].isDone()).as("두 요청 모두 잠금에서 대기 중이어야 한다").isFalse();
			}); // 커밋 -> 잠금 해제

			List<Integer> codes = List.of(futures[0].get(15, TimeUnit.SECONDS), futures[1].get(15, TimeUnit.SECONDS));
			assertThat(codes).containsExactlyInAnyOrder(204, 404);
			assertThat(사진수(id)).isZero();
			assertThat(photoStorage.get(본키)).isEmpty();
			assertThat(photoStorage.get(썸키)).isEmpty();
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	// ============ SecurityConfig: 공개로 열린 것은 사진 보기 GET 하나뿐 ============

	@Test
	void POST_DELETE_사진_주소는_여전히_비로그인_401이다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		int 올리기 = mockMvc.perform(multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림("jpg", 10, 10))).with(csrf()))
				.andReturn().getResponse().getStatus();
		assertThat(올리기).isEqualTo(401);
		assertThat(지우기(id, photoId, null).getStatus()).isEqualTo(401);
		// 만료·변조 토큰은 공개 주소가 아니므로 여전히 401 (GET 사진 보기만 토큰을 무시한다)
		assertThat(지우기(id, photoId, new Cookie("access_token", "변조")).getStatus()).isEqualTo(401);
	}

	@Test
	void GET_사진_목록_주소와_그_밖의_사진_아래_주소는_공개로_열리지_않는다() throws Exception {
		long id = 글올리기(쿠키);
		long photoId = 사진올리기(id);

		// 목록 주소 GET은 만들지 않았다: 비로그인 401(공개 규칙에 안 걸림), 로그인하면 405(POST만 있는 주소)
		assertThat(mockMvc.perform(get("/market-items/" + id + "/photos")).andReturn().getResponse().getStatus())
				.isEqualTo(401);
		assertThat(mockMvc.perform(get("/market-items/" + id + "/photos").cookie(쿠키)).andReturn().getResponse().getStatus())
				.isEqualTo(405);
		// 사진 번호 아래 한 칸 더, 그리고 다른 글 아래 이름 바꾼 주소도 열려 있지 않다
		assertThat(mockMvc.perform(get("/market-items/" + id + "/photos/" + photoId + "/extra")).andReturn().getResponse()
				.getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(get("/market-items/" + id + "/photo/" + photoId)).andReturn().getResponse().getStatus())
				.isEqualTo(401);
	}
}
