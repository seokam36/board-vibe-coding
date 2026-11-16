package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.permit.service.PermitVerificationService;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.PhotoStorageException;
import com.example.demo.photo.repository.StoredPhoto;
import com.example.demo.photo.service.PhotoCleanupJob;
import com.example.demo.photo.service.PhotoDeleteQueue;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// S20-a review 수정 검사: (1) 허가서 key 로그 마스킹 (2) 저장 전 대기 표 쪽지와 청소부 겹침 503 (3) 서버 탓 실패의 하루 횟수 되돌리기 (4) 해시 부분 UNIQUE.
// 창고는 가짜(고장나는창고)로 바꿔 끼워 삭제·저장 실패를 일부러 만든다(MarketItemPhotoCleanupTests와 같은 방식). 끝나면 되돌린다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PermitReviewFixTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired StringRedisTemplate redis;
	@Autowired PermitVerificationService permitService;
	@Autowired PhotoDeleteQueue photoDeleteQueue;
	@Autowired PhotoCleanupJob cleanupJob;
	@Value("${app.photo.local-dir}") String uploadDir;

	static final String URL = "/users/me/permit-verification";
	static final String 비밀 = "SECRETPATH-C:/disk/permits";

	// 삭제·저장을 일부러 실패시키거나, 저장 직후 무언가를 끼워 넣을 수 있는 가짜 창고
	static class 고장나는창고 implements PhotoStorage {
		final PhotoStorage 진짜;
		volatile boolean 지우기실패;
		volatile boolean 넣기실패;
		volatile Runnable put_직후;

		고장나는창고(PhotoStorage 진짜) {
			this.진짜 = 진짜;
		}

		@Override
		public void put(String key, byte[] bytes, String contentType) {
			if (넣기실패) {
				throw new PhotoStorageException("일부러 실패 " + key + " " + 비밀, new RuntimeException("디스크 꽉 참"));
			}
			진짜.put(key, bytes, contentType);
			if (put_직후 != null) {
				put_직후.run();
			}
		}

		@Override
		public Optional<byte[]> get(String key) {
			return 진짜.get(key);
		}

		@Override
		public Optional<StoredPhoto> open(String key) {
			return 진짜.open(key);
		}

		@Override
		public void delete(String key) {
			if (지우기실패) {
				// 로컬 창고처럼 예외 메시지에 key와 경로가 들어가는 상황
				// 허가서 key일 때만 비밀 경로를 넣는다(중고 key 로그는 기존대로 예외 전체가 남는 것이 정상이라 비밀 검사에 걸리면 안 된다)
				String 덧붙임 = key.startsWith("permits/") ? " " + 비밀 : "";
				throw new PhotoStorageException("삭제 실패 " + key + 덧붙임, new RuntimeException("원인 " + key));
			}
			진짜.delete(key);
		}
	}

	User 회원;
	Cookie 쿠키;
	고장나는창고 창고;
	PhotoStorage 진짜창고;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		회원 = TestLogin.사용자(userRepository, "rfuser");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		진짜창고 = (PhotoStorage) ReflectionTestUtils.getField(photoDeleteQueue, "photoStorage");
		창고 = new 고장나는창고(진짜창고);
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 창고);
		ReflectionTestUtils.setField(permitService, "photoStorage", 창고);
	}

	@AfterEach
	void 정리() throws Exception {
		if (진짜창고 != null) {
			ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 진짜창고);
			ReflectionTestUtils.setField(permitService, "photoStorage", 진짜창고);
		}
		jdbc.update("delete from photo_delete_queue");
		jdbc.update("delete from permit_requests");
		postRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(Path.of(uploadDir));
		Set<String> keys = redis.keys("permit-upload-day:*");
		if (keys != null && !keys.isEmpty()) {
			redis.delete(keys);
		}
	}

	// ---------- 도우미 ----------

	private static byte[] 그림() throws Exception {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, 400, 300);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}

	private int 신청(byte[] 바이트) throws Exception {
		return mockMvc.perform(multipart(URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 바이트))
				.param("consent", "true").cookie(쿠키).with(csrf())).andReturn().getResponse().getStatus();
	}

	private int 신청() throws Exception {
		return 신청(그림());
	}

	private int 철회() throws Exception {
		return mockMvc.perform(delete(URL).cookie(쿠키).with(csrf())).andReturn().getResponse().getStatus();
	}

	private long 행수() {
		return jdbc.queryForObject("select count(*) from permit_requests", Long.class);
	}

	private long 큐줄수() {
		return jdbc.queryForObject("select count(*) from photo_delete_queue", Long.class);
	}

	private long 파일수() throws Exception {
		Path dir = Path.of(uploadDir);
		if (!Files.exists(dir)) {
			return 0;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(Files::isRegularFile).count();
		}
	}

	private int 카운터() {
		String v = redis.opsForValue().get("permit-upload-day:" + 회원.getId() + ":" + LocalDate.now(ZoneId.of("Asia/Seoul")));
		return v == null ? 0 : Integer.parseInt(v);
	}

	private String 큐키() {
		return jdbc.queryForObject("select storage_key from photo_delete_queue", String.class);
	}

	// 허가서 key가 로그 어디에도 없어야 한다 (key 전체·UUID·permits/·경로·예외 메시지)
	private void 키가_로그에_없다(CapturedOutput output, String key) {
		String uuid = key.substring(key.lastIndexOf('/') + 1, key.lastIndexOf('.'));
		String 로그 = output.getAll();
		assertThat(로그).doesNotContain(key).doesNotContain(uuid).doesNotContain("permits/").doesNotContain("SECRETPATH");
	}

	// ---------- 1. 허가서 key 로그 마스킹 ----------

	@Test
	void 철회_때_창고_삭제가_실패해도_로그에_key가_없고_허가서_사진_문구만_있고_key는_대기_표에_남는다(CapturedOutput output) throws Exception {
		assertThat(신청()).isEqualTo(201);
		String key = jdbc.queryForObject("select photo_key from permit_requests", String.class);
		assertThat(큐줄수()).isZero();
		창고.지우기실패 = true;

		assertThat(철회()).isEqualTo(204); // 삭제가 실패해도 응답은 그대로
		assertThat(행수()).isZero();
		assertThat(큐줄수()).as("지우지 못한 key는 대기 표에 남는다").isEqualTo(1);
		assertThat(큐키()).isEqualTo(key);
		키가_로그에_없다(output, key);
		assertThat(output.getAll()).contains("허가서 사진 1건").contains("PhotoStorageException");
	}

	@Test
	void 탈퇴_때_창고_삭제가_실패해도_로그에_key가_없고_허가서_사진_문구만_있고_key는_대기_표에_남는다(CapturedOutput output) throws Exception {
		assertThat(신청()).isEqualTo(201);
		String key = jdbc.queryForObject("select photo_key from permit_requests", String.class);
		창고.지우기실패 = true;

		MockHttpServletResponse r = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키)
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"unused-password\"}")).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(204);
		assertThat(행수()).isZero();
		assertThat(큐줄수()).isEqualTo(1);
		assertThat(큐키()).isEqualTo(key);
		키가_로그에_없다(output, key);
		assertThat(output.getAll()).contains("허가서 사진 1건");
	}

	@Test
	void 청소부가_허가서_사진_삭제를_5번째_실패해도_로그에_key가_없고_성공하면_줄과_파일이_사라진다(CapturedOutput output) throws Exception {
		assertThat(신청()).isEqualTo(201);
		String key = jdbc.queryForObject("select photo_key from permit_requests", String.class);
		창고.지우기실패 = true;
		assertThat(철회()).isEqualTo(204);
		assertThat(파일수()).isEqualTo(1);

		// 시간이 지나고 이미 4번 실패한 상황 -> 이번이 5번째(경고 기준)
		jdbc.update("update photo_delete_queue set created_at = now() - interval '1 hour', attempts = 4");
		assertThat(cleanupJob.runOnce()).isEqualTo(1);
		assertThat(jdbc.queryForObject("select attempts from photo_delete_queue", Integer.class)).isEqualTo(5);
		assertThat(output.getAll()).contains("5번째 실패").contains("허가서 사진 1건");
		키가_로그에_없다(output, key);

		// 창고가 살아나면 청소부가 지운다
		창고.지우기실패 = false;
		assertThat(cleanupJob.runOnce()).isEqualTo(1);
		assertThat(큐줄수()).isZero();
		assertThat(파일수()).isZero();
	}

	@Test
	void 중고_사진_key만_있을_때는_삭제_실패_로그에_기존처럼_key가_찍힌다(CapturedOutput output) {
		String 중고키 = "market/1/abc-market-photo.jpg";
		photoDeleteQueue.enqueue(List.of(중고키));
		창고.지우기실패 = true;
		photoDeleteQueue.deleteFilesAndDequeue(List.of(중고키));
		assertThat(output.getAll()).contains(중고키).doesNotContain("허가서 사진");
		assertThat(큐줄수()).isEqualTo(1);
	}

	@Test
	void 청소부의_중고_사진_5번째_실패_로그에는_기존처럼_key가_찍힌다(CapturedOutput output) {
		String 중고키 = "market/1/abc-market-photo.jpg";
		photoDeleteQueue.enqueue(List.of(중고키));
		jdbc.update("update photo_delete_queue set created_at = now() - interval '1 hour', attempts = 4");
		창고.지우기실패 = true;
		assertThat(cleanupJob.runOnce()).isEqualTo(1);
		assertThat(output.getAll()).contains("5번째 실패").contains(중고키).doesNotContain("허가서 사진");
	}

	@Test
	void 허가서_key가_섞인_삭제_실패_로그는_허가서_key만_가리고_중고_key는_남긴다(CapturedOutput output) {
		String 중고키 = "market/1/abc-market-photo.jpg";
		String 허가서키 = "permits/7/0f0f0f0f-aaaa-bbbb-cccc-123456789abc.jpg";
		List<String> keys = new ArrayList<>(List.of(중고키, 허가서키));
		photoDeleteQueue.enqueue(keys);
		창고.지우기실패 = true;
		photoDeleteQueue.deleteFilesAndDequeue(keys);
		String 로그 = output.getAll();
		assertThat(로그).contains(중고키).contains("허가서 사진 1건");
		assertThat(로그).doesNotContain(허가서키).doesNotContain("0f0f0f0f").doesNotContain("SECRETPATH");
		assertThat(큐줄수()).isEqualTo(2);
	}

	// ---------- 2. 저장 전 대기 표 쪽지 ----------

	@Test
	void 신청이_성공하면_대기_표가_비어_있고_파일_1개와_행_1개가_있다() throws Exception {
		assertThat(신청()).isEqualTo(201);
		assertThat(큐줄수()).isZero();
		assertThat(행수()).isEqualTo(1);
		assertThat(파일수()).isEqualTo(1);
	}

	@Test
	void 저장_직후_쪽지가_있고_attempts가_0이다() throws Exception {
		// 저장 "전에" 쪽지를 적는다: put 직후 시점에 쪽지 1줄(attempts 0)이 있어야 한다
		long[] 줄수 = new long[1];
		int[] 시도 = new int[1];
		창고.put_직후 = () -> {
			줄수[0] = 큐줄수();
			시도[0] = jdbc.queryForObject("select attempts from photo_delete_queue", Integer.class);
		};
		assertThat(신청()).isEqualTo(201);
		assertThat(줄수[0]).isEqualTo(1);
		assertThat(시도[0]).isZero();
	}

	@Test
	void 청소부가_이미_집은_쪽지면_503이고_행이_없고_파일과_대기_표가_정리되고_횟수가_되돌려진다() throws Exception {
		창고.put_직후 = () -> jdbc.update("update photo_delete_queue set attempts = 1"); // 청소부가 집어 간 상황
		assertThat(신청()).isEqualTo(503);
		assertThat(행수()).isZero();
		assertThat(파일수()).isZero();
		assertThat(큐줄수()).isZero();
		assertThat(카운터()).isZero();
	}

	@Test
	void 청소부가_쪽지를_이미_지웠으면_503이고_행이_없다() throws Exception {
		창고.put_직후 = () -> jdbc.update("delete from photo_delete_queue"); // 청소부가 파일까지 지운 상황
		assertThat(신청()).isEqualTo(503);
		assertThat(행수()).isZero();
		assertThat(파일수()).isZero();
		assertThat(큐줄수()).isZero();
		assertThat(카운터()).isZero();
	}

	@Test
	void 창고_저장이_실패하면_500_예외이고_행이_없고_대기_표가_정리되고_횟수가_되돌려진다(CapturedOutput output) throws Exception {
		창고.넣기실패 = true;
		// 창고 예외는 전용 처리기가 없어 MockMvc에서는 예외로 나온다(진짜 서버에서는 500). 중고 사진 테스트와 같은 방식
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> 신청()).hasRootCauseMessage("디스크 꽉 참");
		assertThat(행수()).isZero();
		assertThat(큐줄수()).isZero();
		assertThat(파일수()).isZero();
		assertThat(카운터()).isZero();
	}

	@Test
	void 동시_신청에서_진_쪽은_INSERT_실패여도_사진과_대기_표가_남지_않는다() throws Exception {
		// 먼저 대기 행 하나를 직접 만들어 "싼 확인"을 통과한 뒤 INSERT에서 부분 UNIQUE에 걸리는 상황을 흉내 낸다
		// (put 직후에 다른 요청이 먼저 PENDING을 넣은 것처럼)
		창고.put_직후 = () -> jdbc.update("insert into permit_requests (user_id, status, created_at) values (?, 'PENDING', now())",
				회원.getId());
		assertThat(신청()).isEqualTo(409);
		assertThat(행수()).isEqualTo(1); // 끼워 넣은 행뿐
		assertThat(파일수()).isZero();
		assertThat(큐줄수()).isZero();
		assertThat(카운터()).as("진 쪽은 횟수를 되돌린다").isZero();
	}

	// ---------- 3. 하루 횟수 되돌리기 ----------

	@Test
	void 처리_자리가_없어_503을_여러_번_맞아도_횟수가_늘지_않아_그날_신청할_수_있다() throws Exception {
		Semaphore permits = (Semaphore) ReflectionTestUtils.getField(permitService, "processPermits");
		long 원래대기 = (long) ReflectionTestUtils.getField(permitService, "processWaitMillis");
		byte[] 사진 = 그림();
		permits.acquire(1);
		try {
			ReflectionTestUtils.setField(permitService, "processWaitMillis", 100L);
			for (int i = 0; i < 5; i++) {
				assertThat(신청(사진)).isEqualTo(503);
				assertThat(카운터()).as((i + 1) + "번째 503 뒤").isZero();
			}
		} finally {
			ReflectionTestUtils.setField(permitService, "processWaitMillis", 원래대기);
			permits.release(1);
		}
		assertThat(신청(사진)).isEqualTo(201);
		assertThat(카운터()).isEqualTo(1);
	}

	@Test
	void 기존_횟수가_있는_상태에서_503을_맞으면_원래_값으로_돌아온다() throws Exception {
		assertThat(신청()).isEqualTo(201);
		assertThat(철회()).isEqualTo(204);
		assertThat(카운터()).isEqualTo(1);
		Semaphore permits = (Semaphore) ReflectionTestUtils.getField(permitService, "processPermits");
		long 원래대기 = (long) ReflectionTestUtils.getField(permitService, "processWaitMillis");
		permits.acquire(1);
		try {
			ReflectionTestUtils.setField(permitService, "processWaitMillis", 100L);
			assertThat(신청()).isEqualTo(503);
		} finally {
			ReflectionTestUtils.setField(permitService, "processWaitMillis", 원래대기);
			permits.release(1);
		}
		assertThat(카운터()).isEqualTo(1);
	}

	@Test
	void 깨진_사진_400은_여전히_횟수에_센다() throws Exception {
		assertThat(신청("text".getBytes(StandardCharsets.UTF_8))).isEqualTo(400);
		assertThat(카운터()).isEqualTo(1);
		assertThat(신청("text".getBytes(StandardCharsets.UTF_8))).isEqualTo(400);
		assertThat(카운터()).isEqualTo(2);
	}

	@Test
	void 성공한_신청은_횟수가_1_올라간_채_유지된다() throws Exception {
		assertThat(신청()).isEqualTo(201);
		assertThat(카운터()).isEqualTo(1);
	}

	// ---------- 4. 해시 부분 UNIQUE ----------

	@Test
	void REJECTED_행_둘이_같은_해시여도_들어가고_APPROVED_둘은_uk_permit_requests_permit_hash로_실패한다() {
		String sql = "insert into permit_requests (user_id, status, permit_number_hash, created_at) values (?, ?, ?, now())";
		String 해시 = "a".repeat(64);
		jdbc.update(sql, 회원.getId(), "REJECTED", 해시);
		jdbc.update(sql, 회원.getId(), "REJECTED", 해시); // 거절된 것끼리는 같은 해시 허용
		jdbc.update(sql, 회원.getId(), "APPROVED", 해시); // 승인 1건은 허용(거절과 해시가 같아도)
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(sql, 회원.getId(), "APPROVED", 해시))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_permit_requests_permit_hash");
		assertThat(행수()).isEqualTo(3);
	}

	@Test
	void 해시가_NULL인_APPROVED_행은_여러_개여도_들어간다() {
		String sql = "insert into permit_requests (user_id, status, created_at) values (?, 'APPROVED', now())";
		jdbc.update(sql, 회원.getId());
		jdbc.update(sql, 회원.getId());
		assertThat(행수()).isEqualTo(2);
	}
}
