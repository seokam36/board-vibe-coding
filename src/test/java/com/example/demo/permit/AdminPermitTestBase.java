package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import com.example.demo.LockRaceSupport;
import com.example.demo.TestLogin;
import com.example.demo.auth.service.TokenService;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S20-b 관리자 허가서 심사 테스트 공통 도우미: 관리자·회원 만들기, 신청 올리기, 승인/반려 호출, 동시 실행.
public abstract class AdminPermitTestBase {

	@Autowired protected MockMvc mockMvc;
	@Autowired protected UserRepository userRepository;
	@Autowired protected PostRepository postRepository;
	@Autowired protected JdbcTemplate jdbc;
	@Autowired protected TokenService tokenService;
	@Autowired protected ObjectMapper om;
	@Autowired protected StringRedisTemplate redis;
	@Autowired protected PhotoStorage photoStorage;
	@Autowired protected PlatformTransactionManager txManager;
	@Autowired protected com.example.demo.permit.service.PermitNumberHash permitNumberHash;
	@Value("${app.photo.local-dir}") protected String uploadDir;

	protected static final String ADMIN_URL = "/admin/permit-requests";
	protected static final String ME_URL = "/users/me/permit-verification";

	protected User 관리자;
	protected Cookie 관리자쿠키;
	protected long 연세id;
	protected long 고려id;

	@BeforeEach
	void 기본준비() throws Exception {
		기본정리();
		관리자 = TestLogin.관리자(userRepository, jdbc, "padmin");
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		연세id = jdbc.queryForObject("select id from schools where name = '연세대학교'", Long.class);
		고려id = jdbc.queryForObject("select id from schools where name = '고려대학교'", Long.class);
	}

	@AfterEach
	void 기본정리() throws Exception {
		jdbc.update("delete from photo_delete_queue");
		jdbc.update("delete from permit_requests");
		postRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(Path.of(uploadDir));
		// 회원별 하루 신청 횟수 키와 구글 하루 전체 상한 키(S21-d)를 지운다
		for (String pattern : List.of("permit-upload-day:*", "permit-vision-day:*")) {
			Set<String> keys = redis.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}

	// ---------- 데이터 만들기 ----------

	protected static byte[] 그림() throws Exception {
		BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, 400, 300);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}

	protected User 회원(String name) {
		return TestLogin.사용자(userRepository, name);
	}

	protected Cookie 쿠키(User u) {
		return TestLogin.쿠키(tokenService, u);
	}

	// 회원이 실제 신청 API로 사진을 올려 PENDING 신청을 만들고 그 id를 돌려준다
	protected long 신청(User u) throws Exception {
		MockHttpServletResponse r = mockMvc.perform(multipart(ME_URL)
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림())).param("consent", "true")
				.cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("신청 응답").isEqualTo(201);
		return jdbc.queryForObject("select id from permit_requests where user_id = ? and status = 'PENDING'", Long.class,
				u.getId());
	}

	// ---------- 호출 ----------

	protected static String 승인본문(Long schoolId, String number) {
		String s = schoolId == null ? null : "\"schoolId\":" + schoolId;
		String n = number == null ? null : "\"permitNumber\":\"" + number + "\"";
		List<String> parts = new ArrayList<>();
		if (s != null) {
			parts.add(s);
		}
		if (n != null) {
			parts.add(n);
		}
		return "{" + String.join(",", parts) + "}";
	}

	protected MockHttpServletResponse 승인(Cookie c, long id, String json, boolean withCsrf) throws Exception {
		var b = put(ADMIN_URL + "/" + id + "/approve").contentType(MediaType.APPLICATION_JSON).content(json);
		if (withCsrf) {
			b.with(csrf());
		}
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	protected MockHttpServletResponse 승인(long id, long schoolId, String number) throws Exception {
		return 승인(관리자쿠키, id, 승인본문(schoolId, number), true);
	}

	protected MockHttpServletResponse 반려(Cookie c, long id, String json, boolean withCsrf) throws Exception {
		var b = put(ADMIN_URL + "/" + id + "/reject").contentType(MediaType.APPLICATION_JSON).content(json);
		if (withCsrf) {
			b.with(csrf());
		}
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	protected MockHttpServletResponse 반려(long id, String reason) throws Exception {
		return 반려(관리자쿠키, id, "{\"reason\":\"" + reason + "\"}", true);
	}

	protected MockHttpServletResponse 목록(Cookie c, String query) throws Exception {
		var b = get(ADMIN_URL + query);
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	protected MockHttpServletResponse 사진(Cookie c, long id) throws Exception {
		var b = get(ADMIN_URL + "/" + id + "/photo");
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	protected MockHttpServletResponse 내상태(User u) throws Exception {
		return mockMvc.perform(get(ME_URL).cookie(쿠키(u))).andReturn().getResponse();
	}

	protected JsonNode json(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	// ---------- DB 조회 ----------

	protected long 행수() {
		return jdbc.queryForObject("select count(*) from permit_requests", Long.class);
	}

	protected long 대기표수() {
		return jdbc.queryForObject("select count(*) from photo_delete_queue", Long.class);
	}

	protected long 파일수() throws Exception {
		Path dir = Path.of(uploadDir);
		if (!Files.exists(dir)) {
			return 0;
		}
		try (Stream<Path> s = Files.walk(dir)) {
			return s.filter(Files::isRegularFile).count();
		}
	}

	protected java.util.Map<String, Object> 신청행(long id) {
		return jdbc.queryForMap("select * from permit_requests where id = ?", id);
	}

	protected Object 인증학교(User u) {
		List<Object> r = jdbc.queryForList("select verified_school_id from users where id = ?", Object.class, u.getId());
		return r.isEmpty() ? "행없음" : r.get(0);
	}

	// ---------- 동시 실행 ----------

	// 지정한 회원 행들을 별도 트랜잭션에서 FOR UPDATE로 잡아 둔 채 요청들을 동시에 출발시키고(전부 잠금에서 대기),
	// 대기 수가 요청 수가 되면 잠금을 풀어(커밋) 한꺼번에 깨운다. 요청마다 상태코드를 돌려준다.
	protected List<Integer> 잠가놓고_동시에(List<Long> 잠글회원id, List<Callable<Integer>> 요청들) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(요청들.size());
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			new TransactionTemplate(txManager).executeWithoutResult(status -> {
				for (Long id : 잠글회원id) {
					jdbc.queryForList("select id from users where id = ? for update", id);
				}
				for (Callable<Integer> c : 요청들) {
					futures.add(pool.submit(c));
				}
				try {
					LockRaceSupport.락대기_기다리기(jdbc, 요청들.size());
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
			});
			List<Integer> out = new ArrayList<>();
			for (Future<Integer> f : futures) {
				out.add(f.get(15, TimeUnit.SECONDS));
			}
			return out;
		} finally {
			pool.shutdown();
			pool.awaitTermination(15, TimeUnit.SECONDS);
		}
	}
}
