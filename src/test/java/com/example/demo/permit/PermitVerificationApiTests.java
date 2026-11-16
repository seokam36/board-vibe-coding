package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
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
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.permit.service.PermitVerificationService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S20-a 완료 조건 검사: 표준입학허가서 인증 "신청" (POST/GET/DELETE /users/me/permit-verification), V14 표, 탈퇴 정리, 로그.
// 사진 창고는 테스트 설정(app.photo.local-dir)의 임시 폴더라 실제 ./uploads를 건드리지 않는다.
// (10MB 초과 413은 MockMvc가 못 보므로 PermitVerificationUploadLimitTests에서 진짜 서버 포트로 본다)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PermitVerificationApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired PlatformTransactionManager txManager;
	@Autowired StringRedisTemplate redis;
	@Autowired PermitVerificationService permitService;
	@Autowired JwtEncoder jwtEncoder;
	@Value("${app.photo.local-dir}") String uploadDir;

	static final String URL = "/users/me/permit-verification";

	User 회원;
	Cookie 쿠키;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		회원 = TestLogin.사용자(userRepository, "puser");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
	}

	@AfterEach
	void 정리() throws Exception {
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

	private MockHttpServletResponse 신청(Cookie c, byte[] 바이트, String 파일명, String 종류, String consent) throws Exception {
		var b = multipart(URL);
		if (바이트 != null) {
			b.file(new MockMultipartFile("file", 파일명, 종류, 바이트));
		}
		if (consent != null) {
			b.param("consent", consent);
		}
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b.with(csrf())).andReturn().getResponse();
	}

	private MockHttpServletResponse 신청JPEG(Cookie c) throws Exception {
		return 신청(c, 그림("jpg", 800, 600), "permit.jpg", "image/jpeg", "true");
	}

	private MockHttpServletResponse 철회(Cookie c) throws Exception {
		var b = delete(URL).with(csrf());
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private MockHttpServletResponse 상태(Cookie c) throws Exception {
		var b = get(URL);
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private String 상태값(Cookie c) throws Exception {
		MockHttpServletResponse r = 상태(c);
		assertThat(r.getStatus()).isEqualTo(200);
		return om.readTree(r.getContentAsString()).get("status").asString();
	}

	private long 행수() {
		return jdbc.queryForObject("select count(*) from permit_requests", Long.class);
	}

	private long 대기행수() {
		return jdbc.queryForObject("select count(*) from permit_requests where status = 'PENDING'", Long.class);
	}

	private long 대기표수() {
		return jdbc.queryForObject("select count(*) from photo_delete_queue", Long.class);
	}

	private List<Path> 창고파일() throws Exception {
		Path dir = Path.of(uploadDir);
		if (!Files.exists(dir)) {
			return List.of();
		}
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(Files::isRegularFile).toList();
		}
	}

	private String 오늘카운터키(User u) {
		return "permit-upload-day:" + u.getId() + ":" + LocalDate.now(ZoneId.of("Asia/Seoul"));
	}

	// ---------- 조건 1: V14 표와 별칭 ----------

	@Test
	void V14_적용_뒤_permit_requests와_school_name_aliases_표가_있다() {
		Long n = jdbc.queryForObject("select count(*) from information_schema.tables "
				+ "where table_name in ('permit_requests', 'school_name_aliases')", Long.class);
		assertThat(n).isEqualTo(2);
		assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '14' and success = true",
				Long.class)).isEqualTo(1);
	}

	@Test
	void 학교_별칭이_5개_학교에_연결돼_있고_전부_소문자다() {
		assertThat(jdbc.queryForObject("select count(distinct school_id) from school_name_aliases", Long.class)).isEqualTo(5);
		assertThat(jdbc.queryForObject("select count(*) from school_name_aliases where alias <> lower(alias)", Long.class))
				.isZero();
		assertThat(jdbc.queryForObject("select count(*) from school_name_aliases", Long.class)).isPositive();
	}

	@Test
	void 소문자가_아닌_별칭을_넣으면_ck_school_name_aliases_lower로_실패한다() {
		Long schoolId = jdbc.queryForObject("select id from schools limit 1", Long.class);
		assertThatThrownBy(() -> jdbc.update("insert into school_name_aliases (school_id, alias) values (?, 'Upper Case')", schoolId))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_school_name_aliases_lower");
	}

	// ---------- 조건 2: 신청 성공 ----------

	@Test
	void JPEG_사진과_동의로_신청하면_201_PENDING이고_행과_사진이_1개씩_생긴다() throws Exception {
		MockHttpServletResponse r = 신청JPEG(쿠키);
		assertThat(r.getStatus()).isEqualTo(201);
		JsonNode body = om.readTree(r.getContentAsString());
		assertThat(body.get("status").asString()).isEqualTo("PENDING");
		assertThat(body.get("requestedAt").asString()).isNotBlank();
		assertThat(대기행수()).isEqualTo(1);
		assertThat(행수()).isEqualTo(1);
		String key = jdbc.queryForObject("select photo_key from permit_requests", String.class);
		assertThat(key).startsWith("permits/");
		assertThat(창고파일()).hasSize(1);
		assertThat(Path.of(uploadDir).relativize(창고파일().get(0)).toString().replace('\\', '/')).startsWith("permits/");
		// 응답에 사진 이름·key가 들어가지 않는다
		assertThat(r.getContentAsString()).doesNotContain("permits/").doesNotContain("photo");
	}

	@Test
	void PNG_사진으로도_신청하면_201이다() throws Exception {
		MockHttpServletResponse r = 신청(쿠키, 그림("png", 500, 400), "permit.png", "image/png", "true");
		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(jdbc.queryForObject("select content_type from permit_requests", String.class)).isEqualTo("image/png");
		assertThat(창고파일()).hasSize(1);
	}

	// ---------- 조건 3: 입력 오류 ----------

	@Test
	void 비로그인은_401이고_아무것도_생기지_않는다() throws Exception {
		assertThat(신청(null, 그림("jpg", 100, 100), "a.jpg", "image/jpeg", "true").getStatus()).isEqualTo(401);
		assertThat(행수()).isZero();
		assertThat(창고파일()).isEmpty();
	}

	@Test
	void CSRF_없이_신청하면_403이다() throws Exception {
		var b = multipart(URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림("jpg", 100, 100)))
				.param("consent", "true").cookie(쿠키);
		assertThat(mockMvc.perform(b).andReturn().getResponse().getStatus()).isEqualTo(403);
		assertThat(행수()).isZero();
	}

	@Test
	void consent가_없으면_400이다() throws Exception {
		assertThat(신청(쿠키, 그림("jpg", 100, 100), "a.jpg", "image/jpeg", null).getStatus()).isEqualTo(400);
		assertThat(행수()).isZero();
		assertThat(창고파일()).isEmpty();
	}

	@Test
	void consent가_false거나_불린이_아니면_400이다() throws Exception {
		assertThat(신청(쿠키, 그림("jpg", 100, 100), "a.jpg", "image/jpeg", "false").getStatus()).isEqualTo(400);
		assertThat(신청(쿠키, 그림("jpg", 100, 100), "a.jpg", "image/jpeg", "abc").getStatus()).isEqualTo(400);
		assertThat(행수()).isZero();
	}

	@Test
	void 파일_파트가_없으면_400이다() throws Exception {
		assertThat(신청(쿠키, null, null, null, "true").getStatus()).isEqualTo(400);
	}

	@Test
	void 빈_파일이면_400이다() throws Exception {
		assertThat(신청(쿠키, new byte[0], "a.jpg", "image/jpeg", "true").getStatus()).isEqualTo(400);
		assertThat(행수()).isZero();
	}

	@Test
	void JPEG나_PNG가_아닌_파일은_이름과_종류가_사진이어도_400이다() throws Exception {
		byte[] 글자 = "이건 사진이 아니라 텍스트다".getBytes(StandardCharsets.UTF_8);
		assertThat(신청(쿠키, 글자, "a.jpg", "image/jpeg", "true").getStatus()).isEqualTo(400);
		assertThat(행수()).isZero();
		assertThat(창고파일()).isEmpty();
	}

	@Test
	void GIF_사진은_400이다() throws Exception {
		assertThat(신청(쿠키, 그림("gif", 50, 50), "a.jpg", "image/jpeg", "true").getStatus()).isEqualTo(400);
		assertThat(창고파일()).isEmpty();
	}

	@Test
	void multipart가_아닌_요청은_415다() throws Exception {
		MockHttpServletResponse r = mockMvc.perform(post(URL).with(csrf()).cookie(쿠키)
				.contentType(MediaType.APPLICATION_JSON).content("{\"consent\":true}")).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(415);
	}

	@Test
	void 토큰은_유효하지만_회원이_없으면_401이다() throws Exception {
		Cookie 유령 = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(신청JPEG(유령).getStatus()).isEqualTo(401);
		assertThat(상태(유령).getStatus()).isEqualTo(401);
		assertThat(철회(유령).getStatus()).isEqualTo(401);
		assertThat(창고파일()).isEmpty();
	}

	// ---------- 조건 4: 409와 경쟁 ----------

	@Test
	void 이미_대기_중인_회원이_또_올리면_409이고_행과_사진은_늘지_않는다() throws Exception {
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(201);
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(409);
		assertThat(대기행수()).isEqualTo(1);
		assertThat(창고파일()).hasSize(1);
	}

	@Test
	void 이미_학교_인증이_있는_회원이_올리면_409다() throws Exception {
		TestLogin.학교_인증_처리(jdbc, "puser", "연세대학교");
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(409);
		assertThat(행수()).isZero();
		assertThat(창고파일()).isEmpty();
	}

	@Test
	void 같은_회원이_동시에_두_번_올리면_하나만_201이고_다른_하나는_409이며_실패한_쪽_사진은_남지_않는다() throws Exception {
		// 회원 행을 FOR UPDATE로 잡아 두면 두 요청 모두 "저장 직전 회원 행 잠금"에서 줄을 선다. 둘이 선 것을 확인한 뒤 풀어 준다.
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			byte[] 사진 = 그림("jpg", 600, 400);
			Callable<Integer> 요청 = () -> 신청(쿠키, 사진, "a.jpg", "image/jpeg", "true").getStatus();
			@SuppressWarnings("unchecked")
			Future<Integer>[] f = new Future[2];
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				jdbc.queryForList("select id from users where id = ? for update", 회원.getId());
				f[0] = pool.submit(요청);
				f[1] = pool.submit(요청);
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 2);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
			});
			List<Integer> 결과 = List.of(f[0].get(15, TimeUnit.SECONDS), f[1].get(15, TimeUnit.SECONDS));
			assertThat(결과).containsExactlyInAnyOrder(201, 409);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
		assertThat(대기행수()).isEqualTo(1);
		assertThat(창고파일()).as("실패한 쪽 사진은 지워져 1개만 남는다").hasSize(1);
		assertThat(대기표수()).as("삭제 성공이면 대기 표는 비어 있다").isZero();
		// 진 쪽(409)은 하루 횟수를 되돌리므로 성공한 1건만 센다 (S20-a review)
		assertThat(redis.opsForValue().get(오늘카운터키(회원))).isEqualTo("1");
	}

	// ---------- 조건 5: 하루 횟수 ----------

	@Test
	void 하루_네_번째_시도는_429이고_철회_후_재제출로도_카운터는_줄지_않는다() throws Exception {
		for (int i = 0; i < 3; i++) {
			assertThat(신청JPEG(쿠키).getStatus()).as((i + 1) + "번째").isEqualTo(201);
			assertThat(철회(쿠키).getStatus()).isEqualTo(204);
		}
		assertThat(redis.opsForValue().get(오늘카운터키(회원))).isEqualTo("3");
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(429);
		assertThat(행수()).isZero();
		assertThat(창고파일()).isEmpty();
	}

	@Test
	void 대기_중_409로_거절된_요청은_하루_횟수에_세지_않는다() throws Exception {
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(201); // 1회
		for (int i = 0; i < 5; i++) {
			assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(409); // 대기 중이라 거절: 세지 않음
		}
		assertThat(redis.opsForValue().get(오늘카운터키(회원))).isEqualTo("1");
		assertThat(철회(쿠키).getStatus()).isEqualTo(204);
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(201); // 2회
		assertThat(철회(쿠키).getStatus()).isEqualTo(204);
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(201); // 3회: 409를 세었다면 여기서 429
	}

	@Test
	void 이미_인증된_회원의_409는_하루_횟수에_세지_않는다() throws Exception {
		TestLogin.학교_인증_처리(jdbc, "puser", "연세대학교");
		for (int i = 0; i < 5; i++) {
			assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(409); // 429가 아니라 계속 409
		}
		assertThat(redis.opsForValue().get(오늘카운터키(회원))).isNull();
	}

	@Test
	void 사진이_깨져_400이_난_시도는_하루_횟수에_센다() throws Exception {
		byte[] 글자 = "text".getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < 3; i++) {
			assertThat(신청(쿠키, 글자, "a.jpg", "image/jpeg", "true").getStatus()).isEqualTo(400);
		}
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(429);
	}

	@Test
	void 하루_횟수는_회원마다_따로_센다() throws Exception {
		User 남 = TestLogin.사용자(userRepository, "pother");
		Cookie 남쿠키 = TestLogin.쿠키(tokenService, 남);
		for (int i = 0; i < 3; i++) {
			신청JPEG(쿠키);
			철회(쿠키);
		}
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(429);
		assertThat(신청JPEG(남쿠키).getStatus()).isEqualTo(201);
	}

	// ---------- 조건 6: 상태 조회 ----------

	@Test
	void 아무것도_없으면_상태는_NONE이다() throws Exception {
		MockHttpServletResponse r = 상태(쿠키);
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode body = om.readTree(r.getContentAsString());
		assertThat(body.get("status").asString()).isEqualTo("NONE");
		assertThat(body.has("requestedAt")).isFalse();
	}

	@Test
	void 신청_뒤_상태는_PENDING이고_신청_시각이_있다() throws Exception {
		신청JPEG(쿠키);
		JsonNode body = om.readTree(상태(쿠키).getContentAsString());
		assertThat(body.get("status").asString()).isEqualTo("PENDING");
		assertThat(body.get("requestedAt").asString()).isNotBlank();
		assertThat(body.has("rejectReason")).isFalse();
	}

	@Test
	void 학교_인증이_있는_회원은_신청_행이_없어도_APPROVED다() throws Exception {
		TestLogin.학교_인증_처리(jdbc, "puser", "연세대학교");
		assertThat(상태값(쿠키)).isEqualTo("APPROVED");
	}

	@Test
	void 상태_조회는_비로그인이면_401이다() throws Exception {
		assertThat(상태(null).getStatus()).isEqualTo(401);
	}

	// ---------- 조건 7: 철회·인증 해제 ----------

	@Test
	void 대기_회원이_철회하면_204이고_행과_사진이_사라지고_상태는_NONE이다() throws Exception {
		신청JPEG(쿠키);
		assertThat(창고파일()).hasSize(1);
		assertThat(철회(쿠키).getStatus()).isEqualTo(204);
		assertThat(행수()).isZero();
		assertThat(창고파일()).isEmpty();
		assertThat(대기표수()).isZero();
		assertThat(상태값(쿠키)).isEqualTo("NONE");
	}

	@Test
	void 학교_인증만_있는_회원이_철회하면_204이고_NONE이_되고_학교_게시판_글쓰기는_403이다() throws Exception {
		TestLogin.학교_인증_처리(jdbc, "puser", "연세대학교");
		long 학교id = jdbc.queryForObject("select id from schools where name = '연세대학교'", Long.class);
		String 글 = "{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 학교id + ",\"category\":\"CHAT\"}";
		// 해제 전에는 쓸 수 있다
		assertThat(mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON).content(글))
				.andReturn().getResponse().getStatus()).isEqualTo(201);

		assertThat(철회(쿠키).getStatus()).isEqualTo(204);
		assertThat(상태값(쿠키)).isEqualTo("NONE");
		assertThat(jdbc.queryForMap("select verified_school_id, verified_at, school_email_hash from users where username = 'puser'")
				.values()).allMatch(v -> v == null);
		assertThat(mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON).content(글))
				.andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void 아무것도_없으면_철회는_404다() throws Exception {
		assertThat(철회(쿠키).getStatus()).isEqualTo(404);
	}

	@Test
	void 철회는_비로그인이면_401이고_CSRF_없으면_403이다() throws Exception {
		assertThat(철회(null).getStatus()).isEqualTo(401);
		assertThat(mockMvc.perform(delete(URL).cookie(쿠키)).andReturn().getResponse().getStatus()).isEqualTo(403);
	}

	@Test
	void 거절된_신청만_있어도_철회하면_행이_지워지고_204다() throws Exception {
		신청JPEG(쿠키);
		jdbc.update("update permit_requests set status = 'REJECTED', reject_reason = 'BLURRY', decided_at = now()");
		assertThat(상태값(쿠키)).isEqualTo("REJECTED");
		assertThat(철회(쿠키).getStatus()).isEqualTo(204);
		assertThat(행수()).isZero();
		assertThat(상태값(쿠키)).isEqualTo("NONE");
	}

	// ---------- 조건 8: 회원 탈퇴 ----------

	@Test
	void 회원_탈퇴하면_대기_신청_행과_사진이_지워진다() throws Exception {
		신청JPEG(쿠키);
		assertThat(창고파일()).hasSize(1);
		MockHttpServletResponse r = mockMvc.perform(delete("/users/me").with(csrf()).cookie(쿠키)
				.contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"unused-password\"}")).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(204);
		assertThat(userRepository.findByUsername("puser")).isEmpty();
		assertThat(행수()).isZero(); // FK CASCADE
		assertThat(창고파일()).as("커밋 뒤 사진 파일 삭제").isEmpty();
		assertThat(대기표수()).as("지운 키는 대기 표에서 빠진다").isZero();
	}

	// ---------- 조건 9: 처리 자리 없음 ----------

	@Test
	void 사진_처리_자리가_없으면_503이고_행과_사진이_없다() throws Exception {
		Semaphore permits = (Semaphore) ReflectionTestUtils.getField(permitService, "processPermits");
		long 원래대기 = (long) ReflectionTestUtils.getField(permitService, "processWaitMillis");
		permits.acquire(1);
		try {
			ReflectionTestUtils.setField(permitService, "processWaitMillis", 200L);
			assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(503);
			assertThat(행수()).isZero();
			assertThat(창고파일()).isEmpty();
		} finally {
			ReflectionTestUtils.setField(permitService, "processWaitMillis", 원래대기);
			permits.release(1);
		}
		// 자리가 나면 정상 처리된다
		assertThat(신청JPEG(쿠키).getStatus()).isEqualTo(201);
	}

	// ---------- 조건 10: 로그 ----------

	@Test
	void 신청과_철회_로그에_파일_이름과_사진_key가_남지_않는다(CapturedOutput output) throws Exception {
		String 비밀파일명 = "secret-passport-name.jpg";
		assertThat(신청(쿠키, 그림("jpg", 400, 300), 비밀파일명, "image/jpeg", "true").getStatus()).isEqualTo(201);
		String key = jdbc.queryForObject("select photo_key from permit_requests", String.class);
		String uuid = key.substring(key.lastIndexOf('/') + 1, key.lastIndexOf('.'));
		assertThat(철회(쿠키).getStatus()).isEqualTo(204);
		// 깨진 사진 경로의 로그도 확인
		신청(쿠키, "bad".getBytes(StandardCharsets.UTF_8), 비밀파일명, "image/jpeg", "true");

		String 로그 = output.getAll();
		assertThat(로그).contains("허가서 신청 접수: userId=" + 회원.getId()).contains("허가서 인증 철회: userId=" + 회원.getId());
		assertThat(로그).doesNotContain(비밀파일명).doesNotContain(uuid).doesNotContain("permits/");
	}
}
