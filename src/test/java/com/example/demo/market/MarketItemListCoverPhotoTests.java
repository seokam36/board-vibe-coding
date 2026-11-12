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
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.imageio.ImageIO;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S13-g5-1 완료 조건 검사: GET /market-items 목록 응답의 대표 사진(coverPhoto).
// MarketItemPhotoViewDeleteTests와 설정(@SpringBootTest·@Import·@AutoConfigureMockMvc)이 같아 Spring 컨텍스트를 같이 쓴다(새로 만들지 않음).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemListCoverPhotoTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired EntityManagerFactory emf;
	@Value("${app.photo.local-dir}") String uploadDir;

	User 회원;
	User 남;
	Cookie 쿠키;
	Cookie 남쿠키;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "cuser", "연세대학교");
		남 = TestLogin.인증된_사용자(userRepository, jdbc, "cother", "고려대학교");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		남쿠키 = TestLogin.쿠키(tokenService, 남);
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

	private long 글올리기(Cookie c, String 제목) throws Exception {
		String json = "{\"title\":\"" + 제목 + "\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(json).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private long 글올리기(Cookie c) throws Exception {
		return 글올리기(c, "제목");
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
	private long 사진올리기(Cookie c, long itemId, String 형식, int w, int h) throws Exception {
		String 종류 = "png".equals(형식) ? "image/png" : "image/jpeg";
		MockHttpServletResponse r = mockMvc.perform(multipart("/market-items/" + itemId + "/photos")
				.file(new MockMultipartFile("file", "a." + 형식, 종류, 그림(형식, w, h))).cookie(c).with(csrf()))
				.andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 사진 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	// 쿼리는 "이름=값&이름=값" 모양. 한글이 들어가도 이중 인코딩되지 않게 param()으로 넘긴다
	private JsonNode 목록(String 쿼리) throws Exception {
		var 요청 = get("/market-items");
		if (쿼리 != null) {
			for (String 쌍 : 쿼리.split("&")) {
				int i = 쌍.indexOf('=');
				요청.param(쌍.substring(0, i), 쌍.substring(i + 1));
			}
		}
		MockHttpServletResponse r = mockMvc.perform(요청).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		return om.readTree(r.getContentAsString());
	}

	private static JsonNode 찾기(JsonNode 목록, long itemId) {
		for (JsonNode n : 목록) {
			if (n.get("id").asLong() == itemId) {
				return n;
			}
		}
		throw new AssertionError("목록에 글 " + itemId + "이(가) 없다");
	}

	private long 학교id(String 이름) {
		return jdbc.queryForObject("select id from schools where name = ?", Long.class, 이름);
	}

	// ============ 완료 조건 1: 여러 장이면 가장 먼저 올린 사진이 대표 ============

	@Test
	void 사진_여러_장_글은_가장_먼저_올린_사진이_대표고_크기_필드가_맞다() throws Exception {
		long id = 글올리기(쿠키);
		long 첫째 = 사진올리기(쿠키, id, "jpg", 1200, 800);
		사진올리기(쿠키, id, "png", 600, 400);
		사진올리기(쿠키, id, "jpg", 300, 300);

		JsonNode cover = 찾기(목록(null), id).get("coverPhoto");

		assertThat(cover.get("id").asLong()).isEqualTo(첫째);
		assertThat(cover.get("width").asInt()).isEqualTo(1200);
		assertThat(cover.get("height").asInt()).isEqualTo(800);
		assertThat(cover.get("thumbWidth").asInt()).isEqualTo(320);
		assertThat(cover.get("thumbHeight").asInt()).isEqualTo(213);
		assertThat(cover.get("contentType").asString()).isEqualTo("image/jpeg");
	}

	// ============ 완료 조건 2: 사진 없는 글은 coverPhoto 키가 있고 null ============

	@Test
	void 사진_없는_글은_coverPhoto_키가_있고_null이다() throws Exception {
		long id = 글올리기(쿠키);

		JsonNode 글 = 찾기(목록(null), id);

		assertThat(글.has("coverPhoto")).as("키가 항상 있어야 한다").isTrue();
		assertThat(글.get("coverPhoto").isNull()).isTrue();
	}

	// ============ 완료 조건 3: 첫 사진을 지우면 다음 사진이 대표 ============

	@Test
	void 첫_사진을_지우면_다음_사진이_대표가_되고_다_지우면_null이다() throws Exception {
		long id = 글올리기(쿠키);
		long 첫째 = 사진올리기(쿠키, id, "jpg", 1200, 800);
		long 둘째 = 사진올리기(쿠키, id, "png", 600, 400);
		assertThat(찾기(목록(null), id).get("coverPhoto").get("id").asLong()).isEqualTo(첫째);

		assertThat(mockMvc.perform(delete("/market-items/" + id + "/photos/" + 첫째).cookie(쿠키).with(csrf()))
				.andReturn().getResponse().getStatus()).isEqualTo(204);

		JsonNode cover = 찾기(목록(null), id).get("coverPhoto");
		assertThat(cover.get("id").asLong()).isEqualTo(둘째);
		assertThat(cover.get("contentType").asString()).isEqualTo("image/png");
		assertThat(cover.get("width").asInt()).isEqualTo(600);

		assertThat(mockMvc.perform(delete("/market-items/" + id + "/photos/" + 둘째).cookie(쿠키).with(csrf()))
				.andReturn().getResponse().getStatus()).isEqualTo(204);
		assertThat(찾기(목록(null), id).get("coverPhoto").isNull()).isTrue();
	}

	// ============ 완료 조건 4: 섞여 있어도 각자 대표 / 필터와 함께 ============

	@Test
	void 여러_글이_섞여_있어도_각_글의_대표가_섞이지_않는다() throws Exception {
		long a = 글올리기(쿠키, "A글");
		long b = 글올리기(남쿠키, "B글");
		long c = 글올리기(쿠키, "C글"); // 사진 없음
		// 일부러 번갈아 올려서 사진 id가 글 순서와 섞이게 한다
		long a1 = 사진올리기(쿠키, a, "jpg", 400, 300);
		long b1 = 사진올리기(남쿠키, b, "png", 500, 200);
		사진올리기(쿠키, a, "jpg", 100, 100);
		사진올리기(남쿠키, b, "jpg", 100, 100);

		JsonNode 전체 = 목록(null);

		assertThat(전체).hasSize(3);
		assertThat(찾기(전체, a).get("coverPhoto").get("id").asLong()).isEqualTo(a1);
		assertThat(찾기(전체, a).get("coverPhoto").get("width").asInt()).isEqualTo(400);
		assertThat(찾기(전체, b).get("coverPhoto").get("id").asLong()).isEqualTo(b1);
		assertThat(찾기(전체, b).get("coverPhoto").get("contentType").asString()).isEqualTo("image/png");
		assertThat(찾기(전체, c).get("coverPhoto").isNull()).isTrue();
	}

	@Test
	void 학교_상태_검색어_필터와_함께_써도_대표가_맞다() throws Exception {
		long 연세 = 글올리기(쿠키, "연세 자전거");
		long 고려 = 글올리기(남쿠키, "고려 의자");
		long 연세사진 = 사진올리기(쿠키, 연세, "jpg", 400, 300);
		long 고려사진 = 사진올리기(남쿠키, 고려, "jpg", 500, 400);

		// schoolId 필터
		JsonNode 학교필터 = 목록("schoolId=" + 학교id("연세대학교"));
		assertThat(학교필터).hasSize(1);
		assertThat(학교필터.get(0).get("coverPhoto").get("id").asLong()).isEqualTo(연세사진);

		// q 필터 (제목 검색)
		JsonNode 검색 = 목록("q=의자");
		assertThat(검색).hasSize(1);
		assertThat(검색.get(0).get("id").asLong()).isEqualTo(고려);
		assertThat(검색.get(0).get("coverPhoto").get("id").asLong()).isEqualTo(고려사진);

		// status 필터 (고려 글만 예약중으로 바꿈)
		jdbc.update("update market_items set status = 'RESERVED' where id = ?", 고려);
		JsonNode 상태 = 목록("status=RESERVED");
		assertThat(상태).hasSize(1);
		assertThat(상태.get(0).get("coverPhoto").get("id").asLong()).isEqualTo(고려사진);

		// 세 필터를 같이
		JsonNode 모두 = 목록("schoolId=" + 학교id("고려대학교") + "&status=RESERVED&q=고려");
		assertThat(모두).hasSize(1);
		assertThat(모두.get(0).get("coverPhoto").get("id").asLong()).isEqualTo(고려사진);
	}

	// ============ 완료 조건 5: 쿼리 수가 글 수와 무관 ============

	@Test
	void 목록_조회의_쿼리_수는_글_수와_사진_수에_비례하지_않는다() throws Exception {
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			long x = 글올리기(쿠키, "하나");
			사진올리기(쿠키, x, "jpg", 100, 100);
			mockMvc.perform(get("/market-items")).andReturn(); // 워밍업
			stats.clear();
			mockMvc.perform(get("/market-items")).andReturn();
			long 한개 = stats.getPrepareStatementCount();

			List<Long> 더 = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				더.add(글올리기(i % 2 == 0 ? 쿠키 : 남쿠키, "글" + i));
			}
			for (int i = 0; i < 5; i++) {
				사진올리기(i % 2 == 0 ? 쿠키 : 남쿠키, 더.get(i), "jpg", 100, 100);
				사진올리기(i % 2 == 0 ? 쿠키 : 남쿠키, 더.get(i), "jpg", 100, 100);
			}
			stats.clear();
			JsonNode 결과 = om.readTree(mockMvc.perform(get("/market-items")).andReturn().getResponse().getContentAsString());
			long 여섯개 = stats.getPrepareStatementCount();

			assertThat(결과).hasSize(6);
			assertThat(여섯개).as("글 6개 쿼리 수(%s) == 글 1개 쿼리 수(%s)", 여섯개, 한개).isEqualTo(한개);
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	// ============ 완료 조건 6: 단건 photos 그대로 / 목록에는 photos 키 없음 ============

	@Test
	void 단건_응답은_photos_배열이_그대로고_목록_응답에는_photos_키가_없다() throws Exception {
		long id = 글올리기(쿠키);
		long 첫째 = 사진올리기(쿠키, id, "jpg", 1200, 800);
		long 둘째 = 사진올리기(쿠키, id, "png", 600, 400);

		JsonNode 단건 = om.readTree(mockMvc.perform(get("/market-items/" + id)).andReturn().getResponse().getContentAsString());
		assertThat(단건.get("photos")).hasSize(2);
		assertThat(단건.get("photos").get(0).get("id").asLong()).isEqualTo(첫째);
		assertThat(단건.get("photos").get(1).get("id").asLong()).isEqualTo(둘째);
		assertThat(단건.has("coverPhoto")).as("단건에는 coverPhoto가 없다").isFalse();

		JsonNode 글 = 찾기(목록(null), id);
		assertThat(글.has("photos")).as("목록에는 photos가 없다").isFalse();
		// 기존 필드는 그대로
		for (String 키 : new String[] { "id", "title", "content", "tradeType", "price", "status", "school", "seller", "createdAt" }) {
			assertThat(글.has(키)).as("목록 항목의 기존 필드 " + 키).isTrue();
		}
	}

	// ============ 완료 조건 8: 대표 사진 1000개 단위 분할 경계 ============

	// 파일 없이 사진 행만 직접 넣는다 (storage_key·thumb_key는 UNIQUE라 이름을 서로 다르게)
	private long 사진행_넣기(long itemId, String 이름, int width) {
		return jdbc.queryForObject("""
				insert into market_item_photos (market_item_id, storage_key, thumb_key, content_type,
				  width, height, thumb_width, thumb_height, created_at)
				values (?, ?, ?, 'image/jpeg', ?, 100, 10, 10, now()) returning id
				""", Long.class, itemId, 이름 + "-main", 이름 + "-thumb", width);
	}

	@Test
	void 글이_1001개여도_분할_경계_양쪽_글의_대표_사진이_각자_맞다() throws Exception {
		// 글 1001개를 한 번에 넣는다 (판매자·학교는 준비에서 만든 것, 가격 제약에 맞게 SALE 1000원)
		jdbc.update("""
				insert into market_items (seller_id, school_id, title, content, trade_type, price, status, created_at)
				select ?, ?, '대량 ' || g, '내용', 'SALE', 1000, 'ON_SALE', now()
				from generate_series(1, 1001) g
				""", 회원.getId(), 학교id("연세대학교"));
		// 목록은 id 내림차순: 0번째=가장 최신, 999번째=첫 묶음의 마지막, 1000번째=둘째 묶음의 유일한 글(가장 오래된 글)
		List<Long> ids = jdbc.queryForList("select id from market_items order by id desc", Long.class);
		assertThat(ids).hasSize(1001);
		long 첫묶음_처음 = ids.get(0);
		long 첫묶음_끝 = ids.get(999);
		long 둘째묶음 = ids.get(1000);
		long 사진없음 = ids.get(500);

		long p1 = 사진행_넣기(첫묶음_처음, "c-first", 111);
		long p2 = 사진행_넣기(첫묶음_끝, "c-edge1", 222); // 대표(더 작은 id)
		사진행_넣기(첫묶음_끝, "c-edge2", 223); // 대표가 아님
		long p3 = 사진행_넣기(둘째묶음, "c-second", 333);

		JsonNode 전체 = 목록(null);

		assertThat(전체).hasSize(1001);
		assertThat(찾기(전체, 첫묶음_처음).get("coverPhoto").get("id").asLong()).isEqualTo(p1);
		assertThat(찾기(전체, 첫묶음_처음).get("coverPhoto").get("width").asInt()).isEqualTo(111);
		assertThat(찾기(전체, 첫묶음_끝).get("coverPhoto").get("id").asLong()).isEqualTo(p2);
		assertThat(찾기(전체, 첫묶음_끝).get("coverPhoto").get("width").asInt()).isEqualTo(222);
		assertThat(찾기(전체, 둘째묶음).get("coverPhoto").get("id").asLong()).isEqualTo(p3);
		assertThat(찾기(전체, 둘째묶음).get("coverPhoto").get("width").asInt()).isEqualTo(333);
		assertThat(찾기(전체, 사진없음).get("coverPhoto").isNull()).isTrue();
	}

	// ============ 완료 조건 7: 창고 이름 비노출 ============

	@Test
	void coverPhoto에는_storageKey와_thumbKey가_노출되지_않는다() throws Exception {
		long id = 글올리기(쿠키);
		사진올리기(쿠키, id, "jpg", 1200, 800);

		MockHttpServletResponse r = mockMvc.perform(get("/market-items")).andReturn().getResponse();
		JsonNode cover = om.readTree(r.getContentAsString()).get(0).get("coverPhoto");

		Set<String> 키들 = new java.util.TreeSet<>();
		cover.propertyNames().forEach(키들::add);
		assertThat(키들).containsExactlyInAnyOrder("id", "width", "height", "thumbWidth", "thumbHeight", "contentType");
		String 본문 = r.getContentAsString();
		assertThat(본문).doesNotContain("storageKey", "thumbKey", "storage_key", "thumb_key");
	}
}
