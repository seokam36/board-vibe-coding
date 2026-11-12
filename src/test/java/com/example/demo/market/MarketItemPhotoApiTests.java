package com.example.demo.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.TokenService;
import com.example.demo.market.service.MarketItemPhotoService;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S13-g2 완료 조건 검사: 중고 글 사진 올리기(POST /market-items/{id}/photos), 글 단건 응답의 photos, 10장 한도와 경쟁, 파일 정리.
// 사진 창고는 테스트 설정(app.photo.local-dir)의 임시 폴더라 실제 ./uploads를 건드리지 않는다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemPhotoApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;
	@Autowired PhotoStorage photoStorage;
	@Autowired MarketItemPhotoService photoService;
	@Value("${app.photo.local-dir}") String uploadDir;

	User 회원; // 연세대 인증 판매자
	User 남;
	User 관리자;
	Cookie 쿠키;
	Cookie 남쿠키;
	Cookie 관리자쿠키;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "puser", "연세대학교");
		남 = TestLogin.인증된_사용자(userRepository, jdbc, "pother", "고려대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "padmin");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		남쿠키 = TestLogin.쿠키(tokenService, 남);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
	}

	@AfterEach
	void 정리() throws Exception {
		jdbc.update("delete from market_items"); // 사진 행은 CASCADE로 같이 지워진다
		postRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(Path.of(uploadDir));
	}

	// ---------- 도우미 ----------

	private long 올리기(Cookie c) throws Exception {
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

	private MockHttpServletResponse 올리기사진(long id, byte[] 바이트, String 파일명, String 종류, Cookie c) throws Exception {
		var b = multipart("/market-items/" + id + "/photos").file(new MockMultipartFile("file", 파일명, 종류, 바이트));
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b.with(csrf())).andReturn().getResponse();
	}

	private MockHttpServletResponse 올리기JPEG(long id, Cookie c) throws Exception {
		return 올리기사진(id, 그림("jpg", 1200, 800), "a.jpg", "image/jpeg", c);
	}

	private long 사진수(long id) {
		return jdbc.queryForObject("select count(*) from market_item_photos where market_item_id = ?", Long.class, id);
	}

	// 창고 폴더 안의 파일 수
	private long 창고파일수() throws Exception {
		Path dir = Path.of(uploadDir);
		if (!Files.exists(dir)) {
			return 0;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(Files::isRegularFile).count();
		}
	}

	// 10장 한도 검사용: 창고 파일 없이 DB 행만 직접 넣는다
	private void 가짜사진행(long itemId, int 개수) {
		for (int i = 0; i < 개수; i++) {
			jdbc.update("insert into market_item_photos (market_item_id, storage_key, thumb_key, content_type, width, height, "
					+ "thumb_width, thumb_height, created_at) values (?, ?, ?, 'image/jpeg', 10, 10, 5, 5, now())", itemId,
					"fake/" + itemId + "/" + i + ".jpg", "fake/" + itemId + "/" + i + "_thumb.jpg");
		}
	}

	// ============ 성공 ============

	@Test
	void 판매자가_JPEG를_올리면_201이고_DB_행과_창고_파일_2개가_생기고_단건에_photos가_보인다() throws Exception {
		long id = 올리기(쿠키);

		MockHttpServletResponse r = 올리기JPEG(id, 쿠키);

		assertThat(r.getStatus()).isEqualTo(201);
		JsonNode b = om.readTree(r.getContentAsString());
		long photoId = b.get("id").asLong();
		assertThat(r.getHeader("Location")).isEqualTo("/market-items/" + id + "/photos/" + photoId);
		assertThat(b.get("contentType").asString()).isEqualTo("image/jpeg");
		assertThat(b.get("width").asInt()).isEqualTo(1200);
		assertThat(b.get("height").asInt()).isEqualTo(800);
		assertThat(b.get("thumbWidth").asInt()).isEqualTo(320);
		assertThat(b.get("thumbHeight").asInt()).isEqualTo(213);
		// 창고 이름은 응답에 나가지 않는다
		assertThat(r.getContentAsString()).doesNotContain("Key").doesNotContain("market/");

		assertThat(사진수(id)).isEqualTo(1);
		var row = jdbc.queryForMap("select storage_key, thumb_key, content_type from market_item_photos where id = ?", photoId);
		assertThat(photoStorage.get((String) row.get("storage_key"))).isPresent();
		assertThat(photoStorage.get((String) row.get("thumb_key"))).isPresent();
		assertThat((String) row.get("storage_key")).startsWith("market/" + id + "/").endsWith(".jpg");
		assertThat((String) row.get("thumb_key")).endsWith("_thumb.jpg");
		assertThat(창고파일수()).isEqualTo(2);

		JsonNode detail = om.readTree(mockMvc.perform(get("/market-items/" + id)).andReturn().getResponse()
				.getContentAsString());
		assertThat(detail.get("photos")).hasSize(1);
		assertThat(detail.get("photos").get(0).get("id").asLong()).isEqualTo(photoId);
		assertThat(detail.get("photos").get(0).get("contentType").asString()).isEqualTo("image/jpeg");
	}

	@Test
	void PNG도_올릴_수_있다() throws Exception {
		long id = 올리기(쿠키);

		MockHttpServletResponse r = 올리기사진(id, 그림("png", 400, 300), "a.png", "image/png", 쿠키);

		assertThat(r.getStatus()).isEqualTo(201);
		JsonNode b = om.readTree(r.getContentAsString());
		assertThat(b.get("contentType").asString()).isEqualTo("image/png");
		String key = jdbc.queryForObject("select storage_key from market_item_photos where id = ?", String.class,
				b.get("id").asLong());
		assertThat(key).endsWith(".png");
		assertThat(창고파일수()).isEqualTo(2);
	}

	@Test
	void 파일_이름이나_content_type이_거짓이어도_내용으로_판별한다() throws Exception {
		// 무엇/왜: 클라이언트가 보낸 이름·종류는 믿지 않고 매직 바이트로 판별한다 (PNG 내용을 .jpg/jpeg로 속여도 PNG로 저장)
		long id = 올리기(쿠키);

		MockHttpServletResponse r = 올리기사진(id, 그림("png", 100, 100), "a.jpg", "image/jpeg", 쿠키);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(om.readTree(r.getContentAsString()).get("contentType").asString()).isEqualTo("image/png");
	}

	@Test
	void 글_단건_응답의_photos는_올린_순이고_사진이_없으면_빈_배열이며_목록에는_photos가_없다() throws Exception {
		long id = 올리기(쿠키);
		JsonNode empty = om.readTree(mockMvc.perform(get("/market-items/" + id)).andReturn().getResponse()
				.getContentAsString());
		assertThat(empty.has("photos")).isTrue();
		assertThat(empty.get("photos")).isEmpty();

		long first = om.readTree(올리기JPEG(id, 쿠키).getContentAsString()).get("id").asLong();
		long second = om.readTree(올리기사진(id, 그림("png", 50, 50), "b.png", "image/png", 쿠키).getContentAsString()).get("id").asLong();

		JsonNode detail = om.readTree(mockMvc.perform(get("/market-items/" + id)).andReturn().getResponse()
				.getContentAsString());
		assertThat(detail.get("photos")).hasSize(2);
		assertThat(detail.get("photos").get(0).get("id").asLong()).isEqualTo(first);
		assertThat(detail.get("photos").get(1).get("id").asLong()).isEqualTo(second);

		// 목록은 N+1을 피하려고 photos 필드가 아예 없다
		JsonNode list = om.readTree(mockMvc.perform(get("/market-items")).andReturn().getResponse().getContentAsString());
		assertThat(list).isNotEmpty();
		assertThat(list.get(0).has("photos")).isFalse();

		// 상태 바꾸기 응답(한 건짜리)에는 photos가 있다
		MockHttpServletResponse status = mockMvc.perform(put("/market-items/" + id + "/status")
				.contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESERVED\"}").cookie(쿠키).with(csrf()))
				.andReturn().getResponse();
		assertThat(status.getStatus()).isEqualTo(200);
		assertThat(om.readTree(status.getContentAsString()).get("photos")).hasSize(2);
	}

	// ============ 권한·상태 ============

	@Test
	void 로그인하지_않았으면_401이다() throws Exception {
		long id = 올리기(쿠키);
		assertThat(올리기사진(id, 그림("jpg", 10, 10), "a.jpg", "image/jpeg", null).getStatus()).isEqualTo(401);
		assertThat(창고파일수()).isZero();
	}

	@Test
	void CSRF_토큰이_없으면_403이다() throws Exception {
		// 기존 글 API와 같은 관례: 쓰기 요청은 CSRF 검사가 로그인 검사보다 먼저다
		long id = 올리기(쿠키);
		int status = mockMvc.perform(multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림("jpg", 10, 10))).cookie(쿠키))
				.andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(403);
	}

	@Test
	void 없는_글이면_404다() throws Exception {
		assertThat(올리기JPEG(999_999, 쿠키).getStatus()).isEqualTo(404);
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 남의_글이면_403이고_관리자도_403이며_아무것도_저장되지_않는다() throws Exception {
		long id = 올리기(쿠키);

		assertThat(올리기JPEG(id, 남쿠키).getStatus()).isEqualTo(403);
		assertThat(올리기JPEG(id, 관리자쿠키).getStatus()).isEqualTo(403);

		assertThat(사진수(id)).isZero();
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 학교_인증이_없는_판매자도_본인_글에_올릴_수_있다() throws Exception {
		// 무엇/왜: 사진 올리기는 수정 규칙을 따른다(학교 인증 불필요). 글을 올린 뒤 인증이 풀린 경우를 DB로 재현
		long id = 올리기(쿠키);
		jdbc.update("update users set verified_school_id = null, verified_at = null where id = ?", 회원.getId());

		assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(201);
	}

	@Test
	void 거래완료_글에는_409이고_아무것도_저장되지_않는다() throws Exception {
		long id = 올리기(쿠키);
		jdbc.update("update market_items set status = 'DONE' where id = ?", id);

		assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(409);

		assertThat(사진수(id)).isZero();
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 남의_거래완료_글이면_409보다_403이_먼저다() throws Exception {
		long id = 올리기(쿠키);
		jdbc.update("update market_items set status = 'DONE' where id = ?", id);

		assertThat(올리기JPEG(id, 남쿠키).getStatus()).isEqualTo(403);
	}

	// ============ 잘못된 입력 400 ============

	@Test
	void 사진이_아닌_파일은_400이고_아무것도_저장되지_않는다() throws Exception {
		long id = 올리기(쿠키);
		byte[] 가짜 = "이건 사진이 아니라 글자예요".getBytes(StandardCharsets.UTF_8);

		assertThat(올리기사진(id, 가짜, "a.jpg", "image/jpeg", 쿠키).getStatus()).isEqualTo(400);

		assertThat(사진수(id)).isZero();
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 빈_파일은_400이다() throws Exception {
		long id = 올리기(쿠키);
		assertThat(올리기사진(id, new byte[0], "a.jpg", "image/jpeg", 쿠키).getStatus()).isEqualTo(400);
		assertThat(사진수(id)).isZero();
	}

	@Test
	void file_파트가_없으면_400이다() throws Exception {
		long id = 올리기(쿠키);
		// 다른 이름의 파트만 있는 multipart 요청
		int status = mockMvc.perform(multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("other", "a.jpg", "image/jpeg", 그림("jpg", 10, 10))).cookie(쿠키).with(csrf()))
				.andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(400);
		assertThat(사진수(id)).isZero();
	}

	@Test
	void multipart가_아닌_요청은_415다() throws Exception {
		long id = 올리기(쿠키);
		int status = mockMvc.perform(post("/market-items/" + id + "/photos").contentType(MediaType.APPLICATION_JSON)
				.content("{}").cookie(쿠키).with(csrf())).andReturn().getResponse().getStatus();
		assertThat(status).isEqualTo(415);
	}

	@Test
	void 입력_400은_404나_403보다_먼저다() throws Exception {
		// 수정 API(가격 400이 404·403보다 먼저)와 같은 순서
		long id = 올리기(쿠키);
		assertThat(올리기사진(id, new byte[0], "a.jpg", "image/jpeg", 남쿠키).getStatus()).isEqualTo(400);
		assertThat(올리기사진(999_999, new byte[0], "a.jpg", "image/jpeg", 쿠키).getStatus()).isEqualTo(400);
	}

	// ============ 10장 한도 ============

	@Test
	void 이미_10장이면_싼_확인에서_409이고_창고에_파일이_아예_안_생긴다() throws Exception {
		long id = 올리기(쿠키);
		가짜사진행(id, 10);

		assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(409);

		assertThat(사진수(id)).isEqualTo(10);
		// 처리 전 싼 확인에서 거절되므로 처리기·창고 저장을 거치지 않는다(잠금 안 검사는 아래 동시 경쟁 테스트가 지킨다)
		assertThat(창고파일수()).as("창고에 파일이 하나도 생기면 안 된다").isZero();
	}

	// 싼 확인에서 걸러지는 요청은 파일 바이트를 읽지도 않는다 (서비스를 직접 불러 getBytes 호출 횟수를 센다)
	private static class 세는파일 extends MockMultipartFile {
		final AtomicInteger 읽은횟수 = new AtomicInteger();

		세는파일(byte[] 내용) {
			super("file", "a.jpg", "image/jpeg", 내용);
		}

		@Override
		public byte[] getBytes() throws java.io.IOException {
			읽은횟수.incrementAndGet();
			return super.getBytes();
		}
	}

	@Test
	void 남의_글_거래완료_글_10장_글이면_바이트를_읽지도_처리기를_부르지도_않는다() throws Exception {
		long 남의글 = 올리기(남쿠키);
		long 거래완료 = 올리기(쿠키);
		jdbc.update("update market_items set status = 'DONE' where id = ?", 거래완료);
		long 열장 = 올리기(쿠키);
		가짜사진행(열장, 10);
		byte[] jpeg = 그림("jpg", 1200, 800);
		LoginUser 나 = new LoginUser(회원.getId(), 회원.getUsername(), 회원.getRole());

		for (long id : new long[] { 남의글, 거래완료, 열장 }) {
			세는파일 파일 = new 세는파일(jpeg);
			assertThatThrownBy(() -> photoService.upload(id, 나, 파일)).isInstanceOf(ResponseStatusException.class);
			assertThat(파일.읽은횟수.get()).as("글 " + id + ": 걸러진 요청은 바이트를 읽으면 안 된다").isZero();
		}
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 처리_자리를_기다리는_동안에는_파일_바이트를_읽지_않는다() throws Exception {
		long id = 올리기(쿠키);
		LoginUser 나 = new LoginUser(회원.getId(), 회원.getUsername(), 회원.getRole());
		Semaphore permits = (Semaphore) ReflectionTestUtils.getField(photoService, "processPermits");
		long 원래대기 = (long) ReflectionTestUtils.getField(photoService, "processWaitMillis");
		세는파일 파일 = new 세는파일(그림("jpg", 1200, 800));
		permits.acquire(2); // 자리를 전부 쥐어 요청이 대기만 하게 한다
		try {
			ReflectionTestUtils.setField(photoService, "processWaitMillis", 200L);
			assertThatThrownBy(() -> photoService.upload(id, 나, 파일))
					.isInstanceOfSatisfying(ResponseStatusException.class,
							e -> assertThat(e.getStatusCode().value()).isEqualTo(503));
			assertThat(파일.읽은횟수.get()).as("자리를 못 얻은 요청은 바이트를 읽으면 안 된다").isZero();
		} finally {
			ReflectionTestUtils.setField(photoService, "processWaitMillis", 원래대기);
			permits.release(2);
		}
		// 자리가 나면 그때 한 번 읽고 올라간다
		photoService.upload(id, 나, 파일);
		assertThat(파일.읽은횟수.get()).isEqualTo(1);
	}

	@Test
	void 열_장째까지는_올라간다() throws Exception {
		long id = 올리기(쿠키);
		가짜사진행(id, 9);

		assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(201);
		assertThat(사진수(id)).isEqualTo(10);
	}

	@Test
	void 아홉_장일_때_두_요청이_동시에_오면_하나만_성공하고_사진은_10장이며_진_쪽_파일은_치워진다() throws Exception {
		// 무엇: 9장 상태에서 같은 글에 동시에 2장. 글 행을 FOR UPDATE로 잠그므로 하나는 기다렸다가 10장을 보고 409
		// 방법: 테스트가 글 행을 먼저 잠가 둔다 -> 두 요청이 모두 잠금에서 대기(처리·창고 저장은 이미 끝남) -> 잠금을 풀면 한 장씩 차례로 들어간다
		long id = 올리기(쿠키);
		가짜사진행(id, 9);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			@SuppressWarnings("unchecked")
			Future<Integer>[] futures = new Future[2];
			new TransactionTemplate(txManager).executeWithoutResult(s -> {
				jdbc.queryForList("select id from market_items where id = ? for update", id);
				for (int i = 0; i < 2; i++) {
					futures[i] = pool.submit(() -> 올리기JPEG(id, 쿠키).getStatus());
				}
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 2);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertThat(futures[0].isDone() || futures[1].isDone()).as("두 요청 모두 잠금에서 대기 중이어야 한다").isFalse();
			}); // 커밋 -> 잠금 해제

			List<Integer> codes = List.of(futures[0].get(15, TimeUnit.SECONDS), futures[1].get(15, TimeUnit.SECONDS));
			assertThat(codes).containsExactlyInAnyOrder(201, 409);
			assertThat(사진수(id)).isEqualTo(10);
			assertThat(창고파일수()).as("이긴 요청의 파일 2개만 남아야 한다").isEqualTo(2);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void 처리_도중_글이_거래완료로_바뀌면_409이고_창고_파일은_치워진다() throws Exception {
		// 무엇/왜: 싼 확인을 통과한 뒤 잠금 대기 중에 상태가 DONE으로 커밋되면, 트랜잭션 안의 재확인이 막고 파일을 정리한다
		long id = 올리기(쿠키);
		int code = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from market_items where id = ? for update", id),
				() -> 올리기JPEG(id, 쿠키).getStatus(),
				() -> jdbc.update("update market_items set status = 'DONE' where id = ?", id));

		assertThat(code).isEqualTo(409);
		assertThat(사진수(id)).isZero();
		assertThat(창고파일수()).isZero();
	}

	@Test
	void 처리_도중_글이_지워지면_404이고_창고_파일은_치워진다() throws Exception {
		long id = 올리기(쿠키);
		int code = LockRaceSupport.잠그고_요청하고_삭제커밋(txManager, jdbc,
				() -> jdbc.queryForList("select id from market_items where id = ? for update", id),
				() -> 올리기JPEG(id, 쿠키).getStatus(),
				() -> jdbc.update("delete from market_items where id = ?", id));

		assertThat(code).isEqualTo(404);
		assertThat(창고파일수()).isZero();
	}

	// ============ 글 삭제 ============

	@Test
	void 글을_지우면_사진_행도_함께_사라진다() throws Exception {
		long id = 올리기(쿠키);
		assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(201);
		assertThat(사진수(id)).isEqualTo(1);

		int status = mockMvc.perform(delete("/market-items/" + id).cookie(쿠키).with(csrf())).andReturn().getResponse()
				.getStatus();

		assertThat(status).isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from market_item_photos", Long.class)).isZero();
		// 창고 파일 정리는 S13-g4라 여기서는 확인하지 않는다
	}

	// ============ 동시 처리 제한 ============

	@Test
	void 이미지_처리_자리가_없으면_기다리다_503이고_자리가_나면_다시_된다() throws Exception {
		long id = 올리기(쿠키);
		Semaphore permits = (Semaphore) ReflectionTestUtils.getField(photoService, "processPermits");
		long 원래대기 = (long) ReflectionTestUtils.getField(photoService, "processWaitMillis");
		permits.acquire(2); // 처리 자리 2개를 전부 쥔다
		try {
			ReflectionTestUtils.setField(photoService, "processWaitMillis", 200L); // 테스트가 5초 걸리지 않게
			assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(503);
			assertThat(사진수(id)).isZero();
			assertThat(창고파일수()).isZero();
		} finally {
			ReflectionTestUtils.setField(photoService, "processWaitMillis", 원래대기);
			permits.release(2);
		}
		assertThat(permits.availablePermits()).isEqualTo(2);
		assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(201);
		assertThat(permits.availablePermits()).as("처리 뒤에는 자리가 돌아온다").isEqualTo(2);
	}

	// ============ 보충 테스트 ============

	@Test
	void 판매자가_탈퇴하면_글과_함께_사진_행도_사라진다() throws Exception {
		// 무엇/왜: 회원 탈퇴 API로 글이 CASCADE로 지워질 때 사진 행도 따라 지워지는지 (조건 8)
		long id = 올리기(쿠키);
		assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(201);
		assertThat(사진수(id)).isEqualTo(1);

		int status = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus();

		assertThat(status).isEqualTo(204);
		assertThat(jdbc.queryForObject("select count(*) from market_item_photos", Long.class)).isZero();
	}

	@Test
	void 글_수정_응답에도_photos가_올린_순으로_담긴다() throws Exception {
		long id = 올리기(쿠키);
		long first = om.readTree(올리기JPEG(id, 쿠키).getContentAsString()).get("id").asLong();
		long second = om.readTree(올리기사진(id, 그림("png", 30, 30), "b.png", "image/png", 쿠키).getContentAsString()).get("id").asLong();

		MockHttpServletResponse r = mockMvc.perform(put("/market-items/" + id).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"새 제목\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":2000}").cookie(쿠키)
				.with(csrf())).andReturn().getResponse();

		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode photos = om.readTree(r.getContentAsString()).get("photos");
		assertThat(photos).hasSize(2);
		assertThat(photos.get(0).get("id").asLong()).isEqualTo(first);
		assertThat(photos.get(1).get("id").asLong()).isEqualTo(second);
		assertThat(r.getContentAsString()).doesNotContain("Key");
	}

	@Test
	void 목록_응답_JSON_원문에는_photos_글자가_없다() throws Exception {
		long id = 올리기(쿠키);
		assertThat(올리기JPEG(id, 쿠키).getStatus()).isEqualTo(201);

		String body = mockMvc.perform(get("/market-items")).andReturn().getResponse().getContentAsString();

		assertThat(body).contains("\"title\"").doesNotContain("photos");
	}

	@Test
	void 글_생성_응답의_photos는_빈_배열이다() throws Exception {
		String json = "{\"title\":\"제목\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		String body = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON).content(json)
				.cookie(쿠키).with(csrf())).andReturn().getResponse().getContentAsString();

		JsonNode photos = om.readTree(body).get("photos");
		assertThat(photos).isNotNull();
		assertThat(photos).isEmpty();
	}

	@Test
	void 창고에_썸네일_저장이_실패하면_예외가_밖으로_나가고_먼저_넣은_본_사진도_치워지고_행은_없다() throws Exception {
		// 무엇/왜: 두 번째 put에서 PhotoStorageException이 나는 가짜 창고로 바꿔 끼워, 먼저 넣은 파일 정리(조건 3)를 본다.
		// (컨텍스트를 늘리지 않으려고 서비스의 final 필드를 잠깐 바꿨다가 되돌린다)
		long id = 올리기(쿠키);
		PhotoStorage 진짜 = (PhotoStorage) ReflectionTestUtils.getField(photoService, "photoStorage");
		PhotoStorage 깨지는창고 = new PhotoStorage() {
			int puts = 0;

			@Override
			public void put(String key, byte[] bytes, String contentType) {
				if (++puts == 2) {
					throw new com.example.demo.photo.repository.PhotoStorageException("일부러 실패", new RuntimeException("디스크 꽉 참"));
				}
				진짜.put(key, bytes, contentType);
			}

			@Override
			public java.util.Optional<byte[]> get(String key) {
				return 진짜.get(key);
			}

			@Override
			public java.util.Optional<com.example.demo.photo.repository.StoredPhoto> open(String key) {
				return 진짜.open(key);
			}

			@Override
			public void delete(String key) {
				진짜.delete(key);
			}
		};
		ReflectionTestUtils.setField(photoService, "photoStorage", 깨지는창고);
		try {
			// 처리하지 못한 예외라 MockMvc에서는 응답 대신 예외로 올라온다(실제 서버에서는 500)
			assertThatThrownBy(() -> 올리기JPEG(id, 쿠키)).hasRootCauseMessage("디스크 꽉 참");
		} finally {
			ReflectionTestUtils.setField(photoService, "photoStorage", 진짜);
		}

		assertThat(사진수(id)).isZero();
		assertThat(창고파일수()).as("먼저 넣은 본 사진이 남으면 안 된다").isZero();
	}

	@Test
	void DB가_사진_종류와_중복_창고_이름과_같은_본_썸네일_키를_거부한다() throws Exception {
		long id = 올리기(쿠키);
		String sql = "insert into market_item_photos (market_item_id, storage_key, thumb_key, content_type, width, height, "
				+ "thumb_width, thumb_height, created_at) values (?, ?, ?, ?, ?, 10, 5, 5, now())";

		assertThatThrownBy(() -> jdbc.update(sql, id, "k/a", "k/a_t", "image/gif", 10))
				.hasMessageContaining("ck_market_item_photos_content_type");
		assertThatThrownBy(() -> jdbc.update(sql, id, "k/same", "k/same", "image/png", 10))
				.hasMessageContaining("ck_market_item_photos_keys_differ");
		assertThatThrownBy(() -> jdbc.update(sql, id, "k/a", "k/a_t", "image/png", 0))
				.hasMessageContaining("ck_market_item_photos_size");

		assertThat(jdbc.update(sql, id, "k/ok", "k/ok_t", "image/png", 10)).isEqualTo(1);
		assertThatThrownBy(() -> jdbc.update(sql, id, "k/ok", "k/other_t", "image/png", 10))
				.hasMessageContaining("uk_market_item_photos_storage_key");
		assertThatThrownBy(() -> jdbc.update(sql, id, "k/other", "k/ok_t", "image/png", 10))
				.hasMessageContaining("uk_market_item_photos_thumb_key");
	}

	@Test
	void 사진_행은_없는_글을_가리킬_수_없고_사진_표에서_users로의_FK는_없다() throws Exception {
		String sql = "insert into market_item_photos (market_item_id, storage_key, thumb_key, content_type, width, height, "
				+ "thumb_width, thumb_height, created_at) values (999999, 'z/a', 'z/b', 'image/png', 1, 1, 1, 1, now())";
		assertThatThrownBy(() -> jdbc.update(sql)).hasMessageContaining("fk_market_item_photos_item");

		Long usersFk = jdbc.queryForObject("select count(*) from information_schema.table_constraints tc "
				+ "join information_schema.constraint_column_usage ccu on tc.constraint_name = ccu.constraint_name "
				+ "where tc.table_name in ('market_item_photos','photo_delete_queue') and tc.constraint_type = 'FOREIGN KEY' "
				+ "and ccu.table_name = 'users'", Long.class);
		assertThat(usersFk).isZero();
	}

	@Test
	void 사진이_깨져서_400이어도_처리_자리는_돌아온다() throws Exception {
		long id = 올리기(쿠키);
		Semaphore permits = (Semaphore) ReflectionTestUtils.getField(photoService, "processPermits");
		올리기사진(id, "가짜".getBytes(StandardCharsets.UTF_8), "a.jpg", "image/jpeg", 쿠키);
		assertThat(permits.availablePermits()).isEqualTo(2);
	}
}
