package com.example.demo.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
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

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.config.SchedulingConfig;
import com.example.demo.market.service.MarketItemPhotoService;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.PhotoStorageException;
import com.example.demo.photo.service.PhotoCleanupJob;
import com.example.demo.photo.service.PhotoDeleteQueue;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.ObjectMapper;

// S13-g4 완료 조건 검사: 글 삭제·탈퇴·사진 지우기·사진 올리기 때 창고 파일 정리와 "지울 파일 대기 표", 그리고 청소부(PhotoCleanupJob.runOnce).
// MarketItemPhotoApiTests와 설정(@SpringBootTest·@Import·@AutoConfigureMockMvc)이 같아 Spring 컨텍스트를 같이 쓴다(새로 만들지 않음).
// 시간 경과는 시계를 가짜로 바꾸지 않고 대기 표 줄의 created_at을 DB에서 과거로 돌려 흉내 낸다(컨텍스트를 늘리지 않으려고).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemPhotoCleanupTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;
	@Autowired PhotoStorage photoStorage;
	@Autowired MarketItemPhotoService photoService;
	@Autowired PhotoDeleteQueue photoDeleteQueue;
	@Autowired PhotoCleanupJob cleanupJob;
	@Autowired ApplicationContext context;
	@Value("${app.photo.local-dir}") String uploadDir;

	User 회원;
	User 남;
	User 관리자;
	Cookie 쿠키;
	Cookie 남쿠키;
	Cookie 관리자쿠키;
	고장나는창고 창고;
	PhotoStorage 진짜창고;

	// 지우기·넣기를 일부러 실패시키거나, 넣은 직후 무언가를 끼워 넣을 수 있는 가짜 창고. 나머지는 진짜 창고에 맡긴다.
	static class 고장나는창고 implements PhotoStorage {
		final PhotoStorage 진짜;
		volatile boolean 지우기실패;
		volatile int 넣기실패번째; // n번째 put에서 실패 (0이면 안 실패)
		volatile Runnable 첫_put_직후;
		volatile Runnable 둘째_put_직후; // 두 번째 put(썸네일) 직후 실행 (S13-g6-1: 느린 올리기 도중 청소부가 끼어드는 상황 흉내)
		volatile String 예외나는키; // 이 키를 지울 때만 일반 RuntimeException을 던진다
		int puts;
		final List<String> 지우기시도 = new ArrayList<>();

		고장나는창고(PhotoStorage 진짜) {
			this.진짜 = 진짜;
		}

		@Override
		public void put(String key, byte[] bytes, String contentType) {
			puts++;
			if (puts == 넣기실패번째) {
				throw new PhotoStorageException("일부러 실패", new RuntimeException("디스크 꽉 참"));
			}
			진짜.put(key, bytes, contentType);
			if (puts == 1 && 첫_put_직후 != null) {
				첫_put_직후.run();
			}
			if (puts == 2 && 둘째_put_직후 != null) {
				둘째_put_직후.run();
			}
		}

		@Override
		public Optional<byte[]> get(String key) {
			return 진짜.get(key);
		}

		@Override
		public void delete(String key) {
			지우기시도.add(key);
			if (key.equals(예외나는키)) {
				throw new IllegalStateException("예상 못 한 오류"); // PhotoStorageException이 아닌 일반 예외
			}
			if (지우기실패) {
				throw new PhotoStorageException("일부러 실패", new RuntimeException("디스크 오류"));
			}
			진짜.delete(key);
		}
	}

	@BeforeEach
	void 준비() throws Exception {
		정리();
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "cuser", "연세대학교");
		남 = TestLogin.인증된_사용자(userRepository, jdbc, "cother", "고려대학교");
		관리자 = TestLogin.관리자(userRepository, jdbc, "cadmin");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		남쿠키 = TestLogin.쿠키(tokenService, 남);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		// 서비스·대기 표 도우미의 창고를 가짜로 바꿔 끼운다(둘 다 같은 진짜 창고에 맡긴다). 끝나면 되돌린다.
		진짜창고 = (PhotoStorage) ReflectionTestUtils.getField(photoDeleteQueue, "photoStorage");
		창고 = new 고장나는창고(진짜창고);
		ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 창고);
		ReflectionTestUtils.setField(photoService, "photoStorage", 창고);
	}

	@AfterEach
	void 정리() throws Exception {
		if (진짜창고 != null) {
			ReflectionTestUtils.setField(photoDeleteQueue, "photoStorage", 진짜창고);
			ReflectionTestUtils.setField(photoService, "photoStorage", 진짜창고);
		}
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

	private MockHttpServletResponse 올리기(long itemId, Cookie c) throws Exception {
		return mockMvc.perform(multipart("/market-items/" + itemId + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림())).cookie(c).with(csrf())).andReturn().getResponse();
	}

	// 사진 한 장을 올리고 사진 id를 돌려준다 (준비용)
	private long 사진올리기(long itemId, Cookie c) throws Exception {
		MockHttpServletResponse r = 올리기(itemId, c);
		assertThat(r.getStatus()).as("준비용 사진 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	// 글에 붙은 사진 파일 이름(본 사진+썸네일) 전부
	private List<String> 글의_키들(long itemId) {
		List<String> keys = new ArrayList<>();
		jdbc.query("select storage_key, thumb_key from market_item_photos where market_item_id = ? order by id", rs -> {
			keys.add(rs.getString(1));
			keys.add(rs.getString(2));
		}, itemId);
		return keys;
	}

	private List<String> 회원의_키들(User u) {
		List<String> keys = new ArrayList<>();
		jdbc.query("select p.storage_key, p.thumb_key from market_item_photos p join market_items m on m.id = p.market_item_id "
				+ "where m.seller_id = ? order by p.id", rs -> {
			keys.add(rs.getString(1));
			keys.add(rs.getString(2));
		}, u.getId());
		return keys;
	}

	private List<String> 큐_키들() {
		return jdbc.queryForList("select storage_key from photo_delete_queue order by id", String.class);
	}

	private long 큐_줄수() {
		return jdbc.queryForObject("select count(*) from photo_delete_queue", Long.class);
	}

	private void 나이_먹이기() {
		// "시간이 지났다"를 흉내: 대기 표 줄을 1시간 전에 적힌 것으로 바꾼다 (min-age 10분보다 오래됨)
		jdbc.update("update photo_delete_queue set created_at = now() - interval '1 hour'");
	}

	private void 파일_있음(List<String> keys) {
		for (String key : keys) {
			assertThat(진짜창고.get(key)).as("파일이 있어야 함: " + key).isPresent();
		}
	}

	private void 파일_없음(List<String> keys) {
		for (String key : keys) {
			assertThat(진짜창고.get(key)).as("파일이 없어야 함: " + key).isEmpty();
		}
	}

	private void 파일_직접_넣기(String key) {
		진짜창고.put(key, new byte[] { 1, 2, 3 }, "image/jpeg");
	}

	private MockHttpServletResponse 글삭제(long itemId, Cookie c) throws Exception {
		return mockMvc.perform(delete("/market-items/" + itemId).cookie(c).with(csrf())).andReturn().getResponse();
	}

	private MockHttpServletResponse 사진삭제(long itemId, long photoId, Cookie c) throws Exception {
		return mockMvc.perform(delete("/market-items/" + itemId + "/photos/" + photoId).cookie(c).with(csrf()))
				.andReturn().getResponse();
	}

	private MockHttpServletResponse 탈퇴(Cookie c) throws Exception {
		return mockMvc.perform(delete("/users/me").cookie(c).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"unused-password\"}")).andReturn().getResponse();
	}

	// ============ 글 삭제 ============

	@Test
	void 본인이_글을_지우면_사진_행과_창고_파일이_모두_사라지고_대기_표는_비어_있다() throws Exception {
		long id = 글올리기(쿠키);
		사진올리기(id, 쿠키);
		사진올리기(id, 쿠키);
		List<String> keys = 글의_키들(id);
		assertThat(keys).hasSize(4);
		파일_있음(keys);

		assertThat(글삭제(id, 쿠키).getStatus()).isEqualTo(204);

		assertThat(jdbc.queryForObject("select count(*) from market_item_photos", Long.class)).isZero();
		파일_없음(keys);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 관리자가_남의_글을_지워도_사진_파일이_사라지고_대기_표는_비어_있다() throws Exception {
		long id = 글올리기(쿠키);
		사진올리기(id, 쿠키);
		List<String> keys = 글의_키들(id);

		assertThat(글삭제(id, 관리자쿠키).getStatus()).isEqualTo(204);

		파일_없음(keys);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 글_삭제_때_창고_지우기가_실패해도_204고_키가_대기_표에_남으며_청소부가_시간이_지난_뒤_지운다() throws Exception {
		long id = 글올리기(쿠키);
		사진올리기(id, 쿠키);
		사진올리기(id, 쿠키);
		List<String> keys = 글의_키들(id);
		창고.지우기실패 = true;

		assertThat(글삭제(id, 쿠키).getStatus()).isEqualTo(204);

		assertThat(큐_키들()).containsExactlyInAnyOrderElementsOf(keys);
		파일_있음(keys);
		창고.지우기실패 = false;

		// 아직 새 줄(10분 안)이라 청소부는 건드리지 않는다
		assertThat(cleanupJob.runOnce()).isZero();
		파일_있음(keys);

		나이_먹이기();
		assertThat(cleanupJob.runOnce()).isEqualTo(4);
		파일_없음(keys);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 남의_글을_지우려다_403이면_대기_표에_아무것도_안_남고_파일도_그대로다() throws Exception {
		long id = 글올리기(쿠키);
		사진올리기(id, 쿠키);
		List<String> keys = 글의_키들(id);

		assertThat(글삭제(id, 남쿠키).getStatus()).isEqualTo(403);
		assertThat(글삭제(id + 1000, 쿠키).getStatus()).isEqualTo(404);

		assertThat(큐_줄수()).isZero();
		파일_있음(keys);
		assertThat(글의_키들(id)).isEqualTo(keys);
	}

	// ============ 회원 탈퇴 ============

	@Test
	void 탈퇴하면_그_회원_글들의_사진_파일이_모두_지워지고_다른_회원_사진은_그대로다() throws Exception {
		long 내글1 = 글올리기(쿠키);
		long 내글2 = 글올리기(쿠키);
		사진올리기(내글1, 쿠키);
		사진올리기(내글1, 쿠키);
		사진올리기(내글2, 쿠키);
		long 남글 = 글올리기(남쿠키);
		사진올리기(남글, 남쿠키);
		List<String> 내키들 = 회원의_키들(회원);
		List<String> 남키들 = 글의_키들(남글);
		assertThat(내키들).hasSize(6);
		assertThat(남키들).hasSize(2);

		assertThat(탈퇴(쿠키).getStatus()).isEqualTo(204);

		파일_없음(내키들);
		파일_있음(남키들);
		assertThat(글의_키들(남글)).isEqualTo(남키들);
		assertThat(jdbc.queryForObject("select count(*) from market_items where seller_id = ?", Long.class, 회원.getId())).isZero();
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 탈퇴_때_창고_지우기가_실패하면_키가_남고_청소부가_나중에_지운다() throws Exception {
		long 글 = 글올리기(쿠키);
		사진올리기(글, 쿠키);
		사진올리기(글, 쿠키);
		List<String> keys = 회원의_키들(회원);
		창고.지우기실패 = true;

		assertThat(탈퇴(쿠키).getStatus()).isEqualTo(204);

		assertThat(큐_키들()).containsExactlyInAnyOrderElementsOf(keys);
		파일_있음(keys);
		창고.지우기실패 = false;
		나이_먹이기();
		assertThat(cleanupJob.runOnce()).isEqualTo(4);
		파일_없음(keys);
		assertThat(큐_줄수()).isZero();
	}

	// ============ 사진 하나 지우기 ============

	@Test
	void 사진_하나를_지우면_파일_2개가_사라지고_대기_표는_비며_다른_사진은_그대로다() throws Exception {
		long id = 글올리기(쿠키);
		long 첫째 = 사진올리기(id, 쿠키);
		사진올리기(id, 쿠키);
		List<String> 전체 = 글의_키들(id);
		List<String> 지울키 = 전체.subList(0, 2);
		List<String> 남을키 = 전체.subList(2, 4);

		assertThat(사진삭제(id, 첫째, 쿠키).getStatus()).isEqualTo(204);

		파일_없음(지울키);
		파일_있음(남을키);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 사진_지우기_때_창고_실패는_204고_키_2개가_남으며_청소부가_지운다() throws Exception {
		long id = 글올리기(쿠키);
		long 사진 = 사진올리기(id, 쿠키);
		List<String> keys = 글의_키들(id);
		창고.지우기실패 = true;

		assertThat(사진삭제(id, 사진, 쿠키).getStatus()).isEqualTo(204);

		assertThat(큐_키들()).containsExactlyInAnyOrderElementsOf(keys);
		창고.지우기실패 = false;
		나이_먹이기();
		assertThat(cleanupJob.runOnce()).isEqualTo(2);
		파일_없음(keys);
		assertThat(큐_줄수()).isZero();
	}

	// ============ 사진 올리기 ============

	@Test
	void 올리기가_성공하면_대기_표의_쪽지가_떼어져_표가_비어_있고_파일은_남는다() throws Exception {
		long id = 글올리기(쿠키);
		사진올리기(id, 쿠키);

		assertThat(큐_줄수()).isZero();
		파일_있음(글의_키들(id));
		// 청소부가 돌아도 쓰는 파일은 그대로다
		나이_먹이기();
		assertThat(cleanupJob.runOnce()).isZero();
		파일_있음(글의_키들(id));
	}

	@Test
	void 처리하는_사이_거래완료가_되어_409면_파일이_치워지고_대기_표는_비어_있다() throws Exception {
		long id = 글올리기(쿠키);
		// 창고에 첫 파일을 넣은 직후 거래완료로 바꿔서, 마지막 짧은 트랜잭션의 재확인이 409를 내게 한다
		창고.첫_put_직후 = () -> jdbc.update("update market_items set status = 'DONE' where id = ?", id);

		assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(409);

		assertThat(jdbc.queryForObject("select count(*) from market_item_photos", Long.class)).isZero();
		assertThat(창고.지우기시도).hasSize(2);
		assertThat(큐_줄수()).isZero();
		assertThat(파일_개수()).isZero();
	}

	@Test
	void 충돌_409_뒤_파일_정리가_실패하면_키가_대기_표에_남고_청소부가_지운다() throws Exception {
		long id = 글올리기(쿠키);
		창고.첫_put_직후 = () -> jdbc.update("update market_items set status = 'DONE' where id = ?", id);
		창고.지우기실패 = true;

		assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(409);

		List<String> 남은키 = 큐_키들();
		assertThat(남은키).hasSize(2);
		assertThat(파일_개수()).as("본 사진·썸네일 파일이 남아 있다").isEqualTo(2);
		창고.지우기실패 = false;
		나이_먹이기();
		assertThat(cleanupJob.runOnce()).isEqualTo(2);
		파일_없음(남은키);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 창고_넣기가_실패하면_먼저_넣은_파일이_치워지고_대기_표는_비어_있으며_정리가_실패하면_남는다() throws Exception {
		long id = 글올리기(쿠키);
		창고.넣기실패번째 = 2; // 썸네일 저장에서 실패

		assertThatThrownBy(() -> 올리기(id, 쿠키)).hasRootCauseMessage("디스크 꽉 참");

		assertThat(큐_줄수()).isZero();
		assertThat(파일_개수()).isZero();
		assertThat(jdbc.queryForObject("select count(*) from market_item_photos", Long.class)).isZero();

		// 이번엔 정리(delete)도 실패하게: 쪽지가 표에 남아야 한다
		창고.puts = 0;
		창고.지우기실패 = true;
		assertThatThrownBy(() -> 올리기(id, 쿠키)).hasRootCauseMessage("디스크 꽉 참");
		List<String> 남은키 = 큐_키들();
		assertThat(남은키).hasSize(2);
		assertThat(파일_개수()).as("먼저 넣은 본 사진은 남아 있다").isEqualTo(1);
		창고.지우기실패 = false;
		나이_먹이기();
		assertThat(cleanupJob.runOnce()).isEqualTo(2);
		assertThat(파일_개수()).isZero();
		assertThat(큐_줄수()).isZero();
	}

	// ============ 느린 올리기 vs 청소부 경합 (S13-g6-1) ============

	// "사진 행은 있는데 창고 파일이 없는" 행의 개수 (깨진 사진이 생겼는지 보는 용도)
	private long 파일_없는_사진_행수() {
		long broken = 0;
		for (String key : jdbc.queryForList("select storage_key from market_item_photos", String.class)) {
			broken += 진짜창고.get(key).isPresent() ? 0 : 1;
		}
		for (String key : jdbc.queryForList("select thumb_key from market_item_photos", String.class)) {
			broken += 진짜창고.get(key).isPresent() ? 0 : 1;
		}
		return broken;
	}

	private long 사진_행수() {
		return jdbc.queryForObject("select count(*) from market_item_photos", Long.class);
	}

	@Test
	void 조건1_정상_올리기는_201이고_그_키의_대기_표_줄은_0개다() throws Exception {
		long id = 글올리기(쿠키);
		List<String> 잡은키 = new ArrayList<>();
		// 올리는 도중(파일 넣은 직후)에는 쪽지 2줄이 attempts 0으로 있어야 한다
		창고.첫_put_직후 = () -> {
			잡은키.addAll(큐_키들());
			assertThat(jdbc.queryForObject("select count(*) from photo_delete_queue where attempts = 0", Long.class)).isEqualTo(2);
		};

		MockHttpServletResponse r = 올리기(id, 쿠키);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(잡은키).hasSize(2);
		assertThat(jdbc.queryForObject("select count(*) from photo_delete_queue where storage_key in (?, ?)", Long.class,
				잡은키.get(0), 잡은키.get(1))).isZero();
		assertThat(글의_키들(id)).containsExactlyInAnyOrderElementsOf(잡은키);
		파일_있음(잡은키);
	}

	@Test
	void 조건2_넣은_뒤_INSERT_전에_쪽지_시도횟수가_1_이상이면_503이고_사진_행은_0개_파일과_쪽지도_정리된다() throws Exception {
		long id = 글올리기(쿠키);
		List<String> 잡은키 = new ArrayList<>();
		창고.둘째_put_직후 = () -> {
			잡은키.addAll(큐_키들());
			jdbc.update("update photo_delete_queue set attempts = 1"); // 청소부가 집어 간 상황
		};

		assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(503);

		assertThat(잡은키).hasSize(2);
		assertThat(사진_행수()).isZero();
		// 조건4: 창고에 그 키 파일 없음, 큐에도 그 키 줄 없음
		파일_없음(잡은키);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 조건2_쪽지_두_줄_중_한_줄만_시도횟수가_1_이상이어도_503이고_사진_행은_0개다() throws Exception {
		long id = 글올리기(쿠키);
		List<String> 잡은키 = new ArrayList<>();
		창고.둘째_put_직후 = () -> {
			잡은키.addAll(큐_키들());
			jdbc.update("update photo_delete_queue set attempts = 1 where id = (select min(id) from photo_delete_queue)");
		};

		assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(503);

		assertThat(사진_행수()).isZero();
		파일_없음(잡은키);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 조건3_넣은_뒤_INSERT_전에_쪽지가_0개면_503이고_사진_행은_0개_파일도_정리된다() throws Exception {
		long id = 글올리기(쿠키);
		List<String> 잡은키 = new ArrayList<>();
		창고.둘째_put_직후 = () -> {
			잡은키.addAll(큐_키들());
			jdbc.update("delete from photo_delete_queue"); // 청소부가 이미 줄을 지운 상황
		};

		assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(503);

		assertThat(잡은키).hasSize(2);
		assertThat(사진_행수()).isZero();
		파일_없음(잡은키);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 조건3_넣은_뒤_INSERT_전에_쪽지가_1개만_남으면_503이고_사진_행은_0개_파일도_정리된다() throws Exception {
		long id = 글올리기(쿠키);
		List<String> 잡은키 = new ArrayList<>();
		창고.둘째_put_직후 = () -> {
			잡은키.addAll(큐_키들());
			jdbc.update("delete from photo_delete_queue where id = (select min(id) from photo_delete_queue)");
		};

		assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(503);

		assertThat(잡은키).hasSize(2);
		assertThat(사진_행수()).isZero();
		파일_없음(잡은키);
		assertThat(큐_줄수()).isZero(); // 남은 한 줄도 바깥 catch가 파일을 지운 뒤 정리한다
	}

	@Test
	void 조건5_올리는_사이_청소부가_쪽지를_처리하면_503이고_파일_없는_사진_행은_생기지_않는다() throws Exception {
		long id = 글올리기(쿠키);
		List<String> 잡은키 = new ArrayList<>();
		int[] 청소부_처리 = new int[1];
		창고.둘째_put_직후 = () -> {
			잡은키.addAll(큐_키들());
			나이_먹이기(); // 10분 넘게 걸린 느린 올리기를 흉내
			청소부_처리[0] = cleanupJob.runOnce(); // 청소부가 "고아 파일"로 보고 지운다
		};

		assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(503);

		assertThat(청소부_처리[0]).as("청소부가 쪽지 2줄을 처리했어야 함").isEqualTo(2);
		assertThat(잡은키).hasSize(2);
		assertThat(사진_행수()).isZero();
		assertThat(파일_없는_사진_행수()).isZero();
		파일_없음(잡은키);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 조건5_청소부가_파일_지우기에_실패해_시도횟수만_올려_둔_경우도_503이고_파일_없는_사진_행은_없다() throws Exception {
		long id = 글올리기(쿠키);
		List<String> 잡은키 = new ArrayList<>();
		창고.둘째_put_직후 = () -> {
			잡은키.addAll(큐_키들());
			나이_먹이기();
			창고.지우기실패 = true; // 청소부가 집었지만(attempts 1) 지우기는 실패
			cleanupJob.runOnce();
			창고.지우기실패 = false; // 이후 바깥 catch의 정리는 성공해야 한다
		};

		assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(503);

		assertThat(사진_행수()).isZero();
		assertThat(파일_없는_사진_행수()).isZero();
		파일_없음(잡은키);
		assertThat(큐_줄수()).isZero();
	}

	// ============ 청소부 ============

	@Test
	void 서버가_죽은_상황_큐에_키가_있고_파일만_있고_사진_행은_없으면_10분_전에는_안_지우고_지나면_지운다() throws Exception {
		파일_직접_넣기("market/1/crash.jpg");
		파일_직접_넣기("market/1/crash_thumb.jpg");
		jdbc.update("insert into photo_delete_queue (storage_key) values ('market/1/crash.jpg'), ('market/1/crash_thumb.jpg')");

		assertThat(cleanupJob.runOnce()).as("아직 새 줄").isZero();
		파일_있음(List.of("market/1/crash.jpg", "market/1/crash_thumb.jpg"));
		assertThat(큐_줄수()).isEqualTo(2);

		// 9분 전에 적힌 줄도 아직 건드리지 않는다
		jdbc.update("update photo_delete_queue set created_at = now() - interval '9 minutes'");
		assertThat(cleanupJob.runOnce()).isZero();
		파일_있음(List.of("market/1/crash.jpg", "market/1/crash_thumb.jpg"));

		나이_먹이기();
		assertThat(cleanupJob.runOnce()).isEqualTo(2);
		파일_없음(List.of("market/1/crash.jpg", "market/1/crash_thumb.jpg"));
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 안전_확인_큐에_있는_키가_사진_행에_살아_있으면_파일은_안_지우고_큐_줄만_지운다() throws Exception {
		long id = 글올리기(쿠키);
		사진올리기(id, 쿠키);
		List<String> keys = 글의_키들(id); // [본 사진, 썸네일]
		// 잘못 적힌 쪽지를 흉내: 쓰는 중인 파일 이름을 대기 표에 직접 넣는다 (본 사진 하나, 썸네일 하나)
		jdbc.update("insert into photo_delete_queue (storage_key) values (?), (?)", keys.get(0), keys.get(1));
		나이_먹이기();

		cleanupJob.runOnce();

		파일_있음(keys);
		assertThat(큐_줄수()).isZero();
		assertThat(글의_키들(id)).isEqualTo(keys);
	}

	@Test
	void 창고_지우기가_계속_실패하면_시도_횟수가_한_번_돌_때마다_1씩_늘고_줄은_남는다() throws Exception {
		파일_직접_넣기("market/2/stuck.jpg");
		jdbc.update("insert into photo_delete_queue (storage_key) values ('market/2/stuck.jpg')");
		나이_먹이기();
		창고.지우기실패 = true;

		for (int i = 1; i <= 6; i++) { // 5번째부터는 경고 로그가 남는다
			assertThat(cleanupJob.runOnce()).isEqualTo(1);
			assertThat(jdbc.queryForObject("select attempts from photo_delete_queue", Integer.class)).isEqualTo(i);
		}
		파일_있음(List.of("market/2/stuck.jpg"));

		// 창고가 살아나면 다음 주기에 지워진다
		창고.지우기실패 = false;
		assertThat(cleanupJob.runOnce()).isEqualTo(1);
		파일_없음(List.of("market/2/stuck.jpg"));
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 이미_없는_파일은_지우기_성공으로_보고_줄을_뺀다() throws Exception {
		jdbc.update("insert into photo_delete_queue (storage_key) values ('market/3/gone.jpg')");
		나이_먹이기();

		assertThat(cleanupJob.runOnce()).isEqualTo(1);

		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 다른_쪽이_잠근_줄은_건너뛰고_잠금이_풀린_뒤_다음_실행에서_처리한다_SKIP_LOCKED() throws Exception {
		파일_직접_넣기("market/4/locked.jpg");
		파일_직접_넣기("market/4/free.jpg");
		jdbc.update("insert into photo_delete_queue (storage_key) values ('market/4/locked.jpg'), ('market/4/free.jpg')");
		나이_먹이기();

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			new TransactionTemplate(txManager).executeWithoutResult(s -> {
				// 이 트랜잭션(= 다른 청소부가 쥐고 있는 상황)이 locked 줄을 잠근다
				jdbc.queryForList("select id from photo_delete_queue where storage_key = 'market/4/locked.jpg' for update");
				try {
					// 다른 스레드(= 다른 연결)에서 청소부를 돌린다. 잠긴 줄을 기다리지 않고 건너뛰어야 한다(기다리면 여기서 시간 초과)
					int 처리 = pool.submit(cleanupJob::runOnce).get(10, TimeUnit.SECONDS);
					assertThat(처리).isEqualTo(1);
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			});
			파일_없음(List.of("market/4/free.jpg"));
			파일_있음(List.of("market/4/locked.jpg"));
			assertThat(큐_키들()).containsExactly("market/4/locked.jpg");

			// 잠금이 풀렸으니 이번엔 처리된다
			assertThat(cleanupJob.runOnce()).isEqualTo(1);
			파일_없음(List.of("market/4/locked.jpg"));
			assertThat(큐_줄수()).isZero();
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	@Test
	void 조건5_청소부가_줄을_잡고_커밋_전이면_올리기_DELETE가_잠금을_기다렸다가_attempts_1을_보고_503이다() throws Exception {
		long id = 글올리기(쿠키);
		List<String> 잡은키 = new ArrayList<>();
		CountDownLatch 잠갔음 = new CountDownLatch(1); // 청소부 역할 트랜잭션이 줄을 잠그고 attempts를 올렸다는 신호
		CountDownLatch 커밋해도됨 = new CountDownLatch(1); // 올리기 DELETE가 실제로 대기에 들어간 걸 확인한 뒤 주는 신호
		AtomicBoolean 대기확인 = new AtomicBoolean(false);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			창고.둘째_put_직후 = () -> {
				잡은키.addAll(큐_키들());
				// (1) 청소부 역할: 별도 연결·트랜잭션에서 두 줄을 잠그고 attempts+1, 신호를 준 뒤 허락이 날 때까지 커밋하지 않는다
				pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(s -> {
					jdbc.update("update photo_delete_queue set attempts = attempts + 1");
					잠갔음.countDown();
					try {
						if (!커밋해도됨.await(20, TimeUnit.SECONDS)) {
							throw new IllegalStateException("커밋 허락 시간 초과");
						}
					} catch (InterruptedException e) {
						throw new IllegalStateException(e);
					}
				}));
				try {
					assertThat(잠갔음.await(10, TimeUnit.SECONDS)).as("청소부 역할이 줄을 잠가야 함").isTrue();
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				// (2) 감시자: 올리기 쪽 DELETE가 pg_stat_activity에서 Lock 대기 중인 걸 볼 때까지 기다린 뒤 커밋을 허락한다 (sleep에 기대지 않음)
				pool.submit(() -> {
					try {
						long 끝 = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
						while (System.nanoTime() < 끝 && !대기확인.get()) {
							Long 대기수 = jdbc.queryForObject(
									"select count(*) from pg_stat_activity where wait_event_type = 'Lock' "
											+ "and query ilike '%delete from photo_delete_queue%' and query not ilike '%pg_stat_activity%'",
									Long.class);
							if (대기수 != null && 대기수 > 0) {
								대기확인.set(true);
							} else {
								Thread.sleep(20);
							}
						}
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					} finally {
						커밋해도됨.countDown(); // 확인 실패여도 멈추지 않게 반드시 푼다 (실패는 아래 assert가 잡는다)
					}
				});
			};

			assertThat(올리기(id, 쿠키).getStatus()).isEqualTo(503);
		} finally {
			커밋해도됨.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).as("보조 스레드가 끝나야 함").isTrue();
		}

		assertThat(대기확인.get()).as("올리기 DELETE가 실제로 줄 잠금을 기다렸어야 함").isTrue();
		assertThat(잡은키).hasSize(2);
		assertThat(사진_행수()).isZero();
		파일_없음(잡은키);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 테스트_설정에서는_스케줄이_꺼져_있어_청소부가_저절로_돌지_않는다() {
		// app.scheduling.enabled=false 이면 SchedulingConfig(@EnableScheduling)가 만들어지지 않는다
		assertThat(context.getBeanNamesForType(SchedulingConfig.class)).isEmpty();
	}

	// ============ 보충: 경계·예외 상황 ============

	@Test
	void 사진_없는_글을_지워도_204고_대기_표는_비어_있다() throws Exception {
		long id = 글올리기(쿠키);

		assertThat(글삭제(id, 쿠키).getStatus()).isEqualTo(204);

		assertThat(jdbc.queryForObject("select count(*) from market_items where id = ?", Long.class, id)).isZero();
		assertThat(큐_줄수()).isZero();
		assertThat(창고.지우기시도).isEmpty();
	}

	@Test
	void 글을_지워도_다른_글의_사진은_그대로다() throws Exception {
		long 지울글 = 글올리기(쿠키);
		long 남길글 = 글올리기(쿠키);
		사진올리기(지울글, 쿠키);
		사진올리기(남길글, 쿠키);
		List<String> 남길키 = 글의_키들(남길글);
		List<String> 지울키 = 글의_키들(지울글);

		assertThat(글삭제(지울글, 쿠키).getStatus()).isEqualTo(204);

		파일_없음(지울키);
		파일_있음(남길키);
		assertThat(글의_키들(남길글)).isEqualTo(남길키);
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 사진_없는_회원이_탈퇴해도_204고_대기_표는_비어_있다() throws Exception {
		글올리기(쿠키); // 사진 없는 글만 있는 판매자

		assertThat(탈퇴(쿠키).getStatus()).isEqualTo(204);

		assertThat(userRepository.findById(회원.getId())).isEmpty();
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 글이_하나도_없는_회원이_탈퇴해도_204다() throws Exception {
		assertThat(탈퇴(남쿠키).getStatus()).isEqualTo(204);

		assertThat(userRepository.findById(남.getId())).isEmpty();
		assertThat(큐_줄수()).isZero();
		assertThat(창고.지우기시도).isEmpty();
	}

	@Test
	void 탈퇴_비밀번호가_틀려_400이면_큐와_파일과_글이_그대로다() throws Exception {
		long 글 = 글올리기(쿠키);
		사진올리기(글, 쿠키);
		List<String> keys = 글의_키들(글);

		int status = mockMvc.perform(delete("/users/me").cookie(쿠키).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"wrong-password\"}")).andReturn().getResponse().getStatus();

		assertThat(status).isEqualTo(400);
		assertThat(큐_줄수()).isZero();
		파일_있음(keys);
		assertThat(글의_키들(글)).isEqualTo(keys);
		assertThat(userRepository.findById(회원.getId())).isPresent();
		assertThat(창고.지우기시도).isEmpty();
	}

	@Test
	void 청소부는_한_번에_100줄만_처리하고_나머지는_다음_실행에서_처리한다() throws Exception {
		jdbc.update("insert into photo_delete_queue (storage_key) select 'market/batch/' || g || '.jpg' from generate_series(1, 150) g");
		나이_먹이기();

		assertThat(cleanupJob.runOnce()).isEqualTo(100);
		assertThat(큐_줄수()).isEqualTo(50);

		assertThat(cleanupJob.runOnce()).isEqualTo(50);
		assertThat(큐_줄수()).isZero();
		assertThat(cleanupJob.runOnce()).isZero();
	}

	@Test
	void 청소부는_한_줄에서_일반_예외가_나도_나머지_줄을_계속_처리한다() throws Exception {
		파일_직접_넣기("market/5/a.jpg");
		파일_직접_넣기("market/5/bad.jpg");
		파일_직접_넣기("market/5/c.jpg");
		jdbc.update("insert into photo_delete_queue (storage_key) values ('market/5/a.jpg'), ('market/5/bad.jpg'), ('market/5/c.jpg')");
		나이_먹이기();
		창고.예외나는키 = "market/5/bad.jpg";

		assertThat(cleanupJob.runOnce()).isEqualTo(3); // 예외 없이 끝까지 돈다

		파일_없음(List.of("market/5/a.jpg", "market/5/c.jpg"));
		파일_있음(List.of("market/5/bad.jpg"));
		assertThat(큐_키들()).containsExactly("market/5/bad.jpg");
		assertThat(jdbc.queryForObject("select attempts from photo_delete_queue", Integer.class)).isEqualTo(1);
	}

	@Test
	void 큐에_같은_키가_두_번_있어도_청소부가_안전하게_지우고_표가_빈다() throws Exception {
		파일_직접_넣기("market/6/dup.jpg");
		jdbc.update("insert into photo_delete_queue (storage_key) values ('market/6/dup.jpg'), ('market/6/dup.jpg')");
		나이_먹이기();

		assertThat(cleanupJob.runOnce()).isEqualTo(2);

		파일_없음(List.of("market/6/dup.jpg"));
		assertThat(큐_줄수()).isZero();
	}

	@Test
	void 큐에_같은_키가_두_번_있어도_사진_행이_쓰는_키면_파일은_안_지우고_줄만_둘_다_지운다() throws Exception {
		long id = 글올리기(쿠키);
		사진올리기(id, 쿠키);
		List<String> keys = 글의_키들(id);
		jdbc.update("insert into photo_delete_queue (storage_key) values (?), (?)", keys.get(0), keys.get(0));
		나이_먹이기();

		cleanupJob.runOnce();

		파일_있음(keys);
		assertThat(큐_줄수()).isZero();
	}

	// 창고 폴더 안의 파일 수
	private long 파일_개수() throws Exception {
		Path dir = Path.of(uploadDir);
		if (!java.nio.file.Files.exists(dir)) {
			return 0;
		}
		try (var s = java.nio.file.Files.walk(dir)) {
			return s.filter(java.nio.file.Files::isRegularFile).count();
		}
	}
}
