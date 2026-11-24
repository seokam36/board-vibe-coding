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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.FileSystemUtils;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.market.domain.ImageSafetyResult;
import com.example.demo.market.domain.SafeSearchLikelihood;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.domain.SafetyVerdict;
import com.example.demo.market.service.ImageSafetyChecker;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S30-b 완료 조건 검사: 중고 사진 업로드 때 자동 검사(BLOCK 거절 / FLAG 자동 신고 / PASS·UNCHECKED 통과)와 reports.source(V18) 제약.
// 진짜 구글은 부르지 않는다: ImageSafetyChecker 빈을 @Primary 가짜로 갈아 끼우고 테스트마다 판정을 지정한다.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MarketItemPhotoAutoReportTests.FakeConfig.class })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MarketItemPhotoAutoReportTests {

	// 판정을 지정할 수 있고 호출 횟수를 세는 가짜 검사기
	static class FakeChecker implements ImageSafetyChecker {
		final AtomicReference<ImageSafetyResult> next = new AtomicReference<>(ImageSafetyResult.pass());
		final AtomicInteger calls = new AtomicInteger();

		@Override
		public ImageSafetyResult check(byte[] image) {
			calls.incrementAndGet();
			return next.get();
		}
	}

	@TestConfiguration
	static class FakeConfig {
		@Bean
		@Primary
		FakeChecker fakeChecker() {
			return new FakeChecker();
		}
	}

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired FakeChecker checker;
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
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "arseller", "연세대학교");
		남 = TestLogin.인증된_사용자(userRepository, jdbc, "arother", "연세대학교"); // 같은 학교라야 중고 글을 신고할 수 있다
		관리자 = TestLogin.관리자(userRepository, jdbc, "aradmin");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		남쿠키 = TestLogin.쿠키(tokenService, 남);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		checker.next.set(ImageSafetyResult.pass());
		checker.calls.set(0);
	}

	@AfterEach
	void 정리() throws Exception {
		jdbc.update("delete from reports");
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
		FileSystemUtils.deleteRecursively(Path.of(uploadDir));
	}

	// ---------- 도우미 ----------

	private static ImageSafetyResult 판정(SafetyVerdict v, SafetyCategory c, SafeSearchLikelihood l) {
		return new ImageSafetyResult(v, c, l);
	}

	private long 글올리기(Cookie c) throws Exception {
		String json = "{\"title\":\"제목\",\"content\":\"내용\",\"tradeType\":\"SALE\",\"price\":1000}";
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(json).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기").isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private static byte[] 그림() throws Exception {
		BufferedImage img = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = img.createGraphics();
		g.setColor(Color.ORANGE);
		g.fillRect(0, 0, 600, 400);
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "jpg", out);
		return out.toByteArray();
	}

	private MockHttpServletResponse 사진올리기(long id, Cookie c) throws Exception {
		var b = multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()));
		if (c != null) {
			b.cookie(c);
		}
		return mockMvc.perform(b.with(csrf())).andReturn().getResponse();
	}

	private long 사진수(long id) {
		return jdbc.queryForObject("select count(*) from market_item_photos where market_item_id = ?", Long.class, id);
	}

	private long 신고수(long itemId) {
		return jdbc.queryForObject("select count(*) from reports where market_item_id = ?", Long.class, itemId);
	}

	private long 대기표수() {
		return jdbc.queryForObject("select count(*) from photo_delete_queue", Long.class);
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

	// ============ 1. BLOCK ============

	@Test
	void BLOCK이면_422이고_사진_창고_대기표에_아무것도_남지_않는다() throws Exception {
		long id = 글올리기(쿠키);
		long 대기표전 = 대기표수();
		checker.next.set(판정(SafetyVerdict.BLOCK, SafetyCategory.ADULT, SafeSearchLikelihood.VERY_LIKELY));

		MockHttpServletResponse r = 사진올리기(id, 쿠키);

		assertThat(r.getStatus()).isEqualTo(422);
		assertThat(checker.calls.get()).isEqualTo(1);
		assertThat(사진수(id)).isZero();
		assertThat(창고파일수()).isZero();
		assertThat(대기표수()).isEqualTo(대기표전);
		assertThat(신고수(id)).isZero(); // BLOCK은 자동 신고도 만들지 않는다
	}

	// ============ 2. FLAG ============

	@Test
	void FLAG_ADULT면_201이고_SEXUAL_자동_신고_1건이_PENDING으로_생긴다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE));

		MockHttpServletResponse r = 사진올리기(id, 쿠키);

		assertThat(r.getStatus()).isEqualTo(201);
		long photoId = om.readTree(r.getContentAsString()).get("id").asLong();
		var rows = jdbc.queryForList(
				"select market_item_id, source, reporter_id, status, reason, detail from reports where market_item_id = ?", id);
		assertThat(rows).hasSize(1);
		var row = rows.get(0);
		assertThat(row.get("source")).isEqualTo("AUTO");
		assertThat(row.get("reporter_id")).isNull();
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("reason")).isEqualTo("SEXUAL");
		String detail = (String) row.get("detail");
		assertThat(detail).contains(String.valueOf(photoId)).contains("성인물").contains("POSSIBLE");
		assertThat(detail).doesNotContain("market/"); // 사진 key는 신고 글에 넣지 않는다
		assertThat(사진수(id)).isEqualTo(1);
	}

	@Test
	void FLAG_VIOLENCE면_사유가_OTHER이고_RACY면_SEXUAL이다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.VIOLENCE, SafeSearchLikelihood.LIKELY));
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);
		var row = jdbc.queryForMap("select reason, detail from reports where market_item_id = ?", id);
		assertThat(row.get("reason")).isEqualTo("OTHER");
		assertThat((String) row.get("detail")).contains("폭력").contains("LIKELY");

		long id2 = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.RACY, SafeSearchLikelihood.POSSIBLE));
		assertThat(사진올리기(id2, 쿠키).getStatus()).isEqualTo(201);
		var row2 = jdbc.queryForMap("select reason, detail from reports where market_item_id = ?", id2);
		assertThat(row2.get("reason")).isEqualTo("SEXUAL");
		assertThat((String) row2.get("detail")).contains("선정성");
	}

	// ============ 3. 같은 글 FLAG 2장 ============

	@Test
	void 같은_글에_FLAG_사진_2장이면_사진은_2장이고_자동_신고는_1건이다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE));

		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);

		assertThat(사진수(id)).isEqualTo(2);
		assertThat(신고수(id)).isEqualTo(1);
		// 뒤 사진도 기존 신고의 설명에 덧붙는다 (사진 번호 2개가 모두 들어 있다)
		String detail = jdbc.queryForObject("select detail from reports where market_item_id = ?", String.class, id);
		for (Long photoId : jdbc.queryForList("select id from market_item_photos where market_item_id = ?", Long.class, id)) {
			assertThat(detail).contains("사진 " + photoId + ", 성인물 POSSIBLE");
		}
	}

	// ============ 4. 기각 뒤 FLAG ============

	@Test
	void 자동_신고를_기각한_뒤_FLAG_사진이면_PENDING_자동_신고가_새로_생긴다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE));
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);
		long reportId = jdbc.queryForObject("select id from reports where market_item_id = ?", Long.class, id);
		assertThat(mockMvc.perform(put("/admin/reports/" + reportId + "/dismiss").cookie(관리자쿠키).with(csrf()))
				.andReturn().getResponse().getStatus()).isEqualTo(200);

		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);

		assertThat(jdbc.queryForObject("select count(*) from reports where market_item_id = ? and status = 'DISMISSED'",
				Long.class, id)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
				"select count(*) from reports where market_item_id = ? and status = 'PENDING' and source = 'AUTO'",
				Long.class, id)).isEqualTo(1);
	}

	// ============ 5. PASS / UNCHECKED ============

	@Test
	void PASS와_UNCHECKED는_201이고_신고가_없다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(ImageSafetyResult.pass());
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);
		checker.next.set(ImageSafetyResult.unchecked());
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);

		assertThat(사진수(id)).isEqualTo(2);
		assertThat(신고수(id)).isZero();
	}

	// ============ 6. 관리자 목록 source ============

	@Test
	void 관리자_목록에_자동_신고는_source_AUTO와_reporter_null이고_회원_신고는_USER다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE));
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);
		// 다른 회원이 같은 글을 직접 신고
		MockHttpServletResponse ur = mockMvc.perform(post("/market-items/" + id + "/reports")
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}").cookie(남쿠키).with(csrf()))
				.andReturn().getResponse();
		assertThat(ur.getStatus()).isEqualTo(201);
		assertThat(ur.getContentAsString()).doesNotContain("\"source\""); // 회원 신고 POST 응답엔 source 없음

		MockHttpServletResponse r = mockMvc.perform(get("/admin/reports?status=PENDING").cookie(관리자쿠키))
				.andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode list = om.readTree(r.getContentAsString());
		JsonNode items = list.isArray() ? list : list.get("content");
		JsonNode auto = null;
		JsonNode user = null;
		for (JsonNode n : items) {
			if ("AUTO".equals(n.get("source").asString())) {
				auto = n;
			} else if ("USER".equals(n.get("source").asString())) {
				user = n;
			}
		}
		assertThat(auto).isNotNull();
		assertThat(auto.get("reporter").isNull()).isTrue();
		assertThat(auto.get("target").get("type").asString()).isEqualTo("MARKET_ITEM");
		assertThat(user).isNotNull();
		assertThat(user.get("reporter").isNull()).isFalse();
	}

	// ============ 7. 기각·글 삭제 ============

	@Test
	void 자동_신고_기각은_200이고_응답_source가_AUTO이며_글을_지우면_자동_신고도_사라진다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE));
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);
		long reportId = jdbc.queryForObject("select id from reports where market_item_id = ?", Long.class, id);

		MockHttpServletResponse r = mockMvc.perform(put("/admin/reports/" + reportId + "/dismiss").cookie(관리자쿠키).with(csrf()))
				.andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode b = om.readTree(r.getContentAsString());
		assertThat(b.get("source").asString()).isEqualTo("AUTO");
		assertThat(b.get("status").asString()).isEqualTo("DISMISSED");

		// 새 자동 신고를 하나 더 만든 뒤 글을 지운다
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);
		assertThat(신고수(id)).isEqualTo(2);
		int del = mockMvc.perform(delete("/market-items/" + id).cookie(쿠키).with(csrf())).andReturn().getResponse()
				.getStatus();
		assertThat(del).isEqualTo(204);
		assertThat(신고수(id)).isZero();
	}

	// ============ 8. 회원 신고는 USER ============

	@Test
	void 회원이_직접_한_중고_글_신고는_DB_source가_USER다() throws Exception {
		long id = 글올리기(쿠키);
		MockHttpServletResponse r = mockMvc.perform(post("/market-items/" + id + "/reports")
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}").cookie(남쿠키).with(csrf()))
				.andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(201);

		assertThat(jdbc.queryForObject("select source from reports where market_item_id = ?", String.class, id))
				.isEqualTo("USER");
	}

	// ============ 9. DB 제약 ============

	private void 신고직접넣기(Long marketItemId, Long postId, Long reporterId, String source, String status) {
		jdbc.update("insert into reports (market_item_id, post_id, reporter_id, reason, status, source, created_at) "
				+ "values (?, ?, ?, 'SPAM', ?, ?, now())", marketItemId, postId, reporterId, status, source);
	}

	@Test
	void DB_AUTO인데_reporter가_있거나_중고글이_없으면_제약_위반이다() throws Exception {
		long id = 글올리기(쿠키);
		assertThatThrownBy(() -> 신고직접넣기(id, null, 회원.getId(), "AUTO", "PENDING"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_auto_shape");

		// 글(post) 대상이면 market_item_id가 없어 AUTO 모양이 아니다
		long postId = postRepository.save(new com.example.demo.post.domain.Post("글 제목", "내용", 회원, null,
				com.example.demo.post.domain.PostCategory.CHAT)).getId();
		assertThatThrownBy(() -> 신고직접넣기(null, postId, null, "AUTO", "PENDING"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_auto_shape");
	}

	@Test
	void DB_같은_글에_PENDING_AUTO_2건은_부분_UNIQUE_위반이고_기각된_것과는_공존한다() throws Exception {
		long id = 글올리기(쿠키);
		신고직접넣기(id, null, null, "AUTO", "PENDING");

		assertThatThrownBy(() -> 신고직접넣기(id, null, null, "AUTO", "PENDING"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_reports_market_item_auto_pending");

		신고직접넣기(id, null, null, "AUTO", "DISMISSED"); // 기각된 자동 신고는 제한에서 빠진다
		assertThat(신고수(id)).isEqualTo(2);
	}

	@Test
	void DB_source에_허용되지_않은_값은_제약_위반이고_기본값은_USER다() throws Exception {
		long id = 글올리기(쿠키);
		assertThatThrownBy(() -> 신고직접넣기(id, null, 남.getId(), "XYZ", "PENDING"))
				.isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_reports_source");

		// source를 안 적고 넣으면(= V18 이전 방식의 행) DEFAULT 'USER'
		jdbc.update("insert into reports (market_item_id, reporter_id, reason, status, created_at) "
				+ "values (?, ?, 'SPAM', 'PENDING', now())", id, 남.getId());
		assertThat(jdbc.queryForObject("select source from reports where market_item_id = ?", String.class, id))
				.isEqualTo("USER");
		assertThat(jdbc.queryForObject(
				"select column_default from information_schema.columns where table_name='reports' and column_name='source'",
				String.class)).contains("USER");
	}

	// ============ 10. 422 본문·로그 ============

	// 무엇: 거절 때 (1) 로그가 실제로 잡히고 글 번호·판정만 남는지(긍정 단언), (2) 서버 메시지는 본문이 아니라 예외에만 있는지.
	// 왜: 예전 단언은 로그를 못 잡아도, 본문이 원래 비어 있어도 통과해 버려 아무것도 검사하지 못했다.
	@Test
	void 거절_422는_거절_로그가_남고_본문에는_서버_메시지가_없으며_로그에_사진_key가_없다(CapturedOutput output) throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.BLOCK, SafetyCategory.ADULT, SafeSearchLikelihood.VERY_LIKELY));

		MvcResult mvc = mockMvc.perform(multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림())).cookie(쿠키).with(csrf())).andReturn();
		MockHttpServletResponse r = mvc.getResponse();

		assertThat(r.getStatus()).isEqualTo(422);
		// 서버 메시지는 예외(ResponseStatusException)에만 있고 응답 본문에는 실리지 않는다(include-message 기본 never)
		assertThat(mvc.getResolvedException()).isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
				.hasMessageContaining("올릴 수 없는 사진입니다.");
		assertThat(r.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("올릴 수 없는 사진")
				.doesNotContain("ADULT");
		// 긍정 단언: 로그 캡처가 실제로 되고, 글 번호와 판정 항목이 남는다
		assertThat(output.getAll()).contains("중고 사진 검사로 거절: itemId=" + id);
		assertThat(output.getAll()).doesNotContain("market/" + id + "/").doesNotContain("storage_key").doesNotContain("JFIF");
	}

	@Test
	void FLAG_통과_업로드는_로그와_신고_설명에_사진_key가_새지_않는다(CapturedOutput output) throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE));

		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(201);

		// 이 경로에서는 사진 key가 실제로 생긴다: 그 값이 로그·detail 어디에도 없어야 한다
		String storageKey = jdbc.queryForObject("select storage_key from market_item_photos where market_item_id = ?", String.class, id);
		String thumbKey = jdbc.queryForObject("select thumb_key from market_item_photos where market_item_id = ?", String.class, id);
		assertThat(storageKey).startsWith("market/" + id + "/");
		String detail = jdbc.queryForObject("select detail from reports where market_item_id = ?", String.class, id);
		assertThat(detail).doesNotContain(storageKey).doesNotContain(thumbKey).doesNotContain("market/");
		assertThat(output.getAll()).doesNotContain(storageKey).doesNotContain(thumbKey).doesNotContain("market/" + id + "/");
	}

	// ============ 12. 3장 FLAG 덧붙임 ============

	@Test
	void 같은_글에_FLAG_사진_3장이면_신고_1건의_설명에_세_장이_올린_순서대로_들어_있다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE));
		java.util.List<Long> photoIds = new java.util.ArrayList<>();
		for (int i = 0; i < 3; i++) {
			MockHttpServletResponse r = 사진올리기(id, 쿠키);
			assertThat(r.getStatus()).isEqualTo(201);
			photoIds.add(om.readTree(r.getContentAsString()).get("id").asLong());
		}

		assertThat(신고수(id)).isEqualTo(1);
		String detail = jdbc.queryForObject("select detail from reports where market_item_id = ?", String.class, id);
		String expected = "자동 탐지: 사진 " + photoIds.get(0) + ", 성인물 POSSIBLE / 사진 " + photoIds.get(1)
				+ ", 성인물 POSSIBLE / 사진 " + photoIds.get(2) + ", 성인물 POSSIBLE";
		assertThat(detail).isEqualTo(expected);
	}

	// ============ 13. 동시 업로드 ============

	@Autowired org.springframework.transaction.PlatformTransactionManager txManager;

	// 무엇: 같은 글에 FLAG 사진 2장을 동시에. 왜: 글 행 FOR UPDATE가 줄을 세워 "신고 1건 + 덧붙임"이 되는지(중복 신고/롤백 없음).
	// 방법: 테스트가 글 행을 먼저 잠가 두고, 두 요청이 모두 잠금에서 기다리게 한 뒤 풀어 준다.
	@Test
	void 같은_글에_FLAG_사진_2장을_동시에_올려도_둘_다_201이고_자동_신고는_1건에_사진_2개가_다_들어_있다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE));
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			@SuppressWarnings("unchecked")
			java.util.concurrent.Future<Integer>[] futures = new java.util.concurrent.Future[2];
			new org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult(s -> {
				jdbc.queryForList("select id from market_items where id = ? for update", id);
				for (int i = 0; i < 2; i++) {
					futures[i] = pool.submit(() -> 사진올리기(id, 쿠키).getStatus());
				}
				try {
					com.example.demo.LockRaceSupport.락대기_기다리기(jdbc, 2);
				} catch (InterruptedException e) {
					throw new IllegalStateException(e);
				}
				assertThat(futures[0].isDone() || futures[1].isDone()).as("두 요청 모두 잠금에서 대기 중이어야 한다").isFalse();
			}); // 커밋 -> 잠금 해제

			assertThat(futures[0].get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
			assertThat(futures[1].get(15, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(201);
		} finally {
			pool.shutdown();
			pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
		}

		assertThat(사진수(id)).isEqualTo(2);
		assertThat(jdbc.queryForObject(
				"select count(*) from reports where market_item_id = ? and source = 'AUTO' and status = 'PENDING'", Long.class, id))
				.isEqualTo(1);
		assertThat(신고수(id)).isEqualTo(1);
		String detail = jdbc.queryForObject("select detail from reports where market_item_id = ?", String.class, id);
		for (Long photoId : jdbc.queryForList("select id from market_item_photos where market_item_id = ?", Long.class, id)) {
			assertThat(detail).contains("사진 " + photoId + ", 성인물 POSSIBLE");
		}
	}

	// ============ 14. 트랜잭션 필수(MANDATORY) ============

	@Autowired com.example.demo.report.service.AutoReportService autoReportService;

	@Test
	void AutoReportService는_트랜잭션_없이_부르면_예외다() {
		ImageSafetyResult flag = 판정(SafetyVerdict.FLAG, SafetyCategory.ADULT, SafeSearchLikelihood.POSSIBLE);

		assertThatThrownBy(() -> autoReportService.reportMarketItemPhoto(null, 1L, flag))
				.isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
		assertThat(jdbc.queryForObject("select count(*) from reports", Long.class)).isZero();
	}

	// ============ 11. 기존 검사가 자동 검사보다 먼저 ============

	@Test
	void 남의_글이면_403_관리자도_403_없는_글_404_거래완료_409_파일없음_400_이고_검사기는_안_불린다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.BLOCK, SafetyCategory.ADULT, SafeSearchLikelihood.VERY_LIKELY));

		assertThat(사진올리기(id, 남쿠키).getStatus()).isEqualTo(403);
		assertThat(사진올리기(id, 관리자쿠키).getStatus()).isEqualTo(403);
		assertThat(사진올리기(999999999L, 쿠키).getStatus()).isEqualTo(404);
		jdbc.update("update market_items set status = 'DONE' where id = ?", id);
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(409);
		long 다른글 = 글올리기(쿠키);
		int 빈파일 = mockMvc.perform(multipart("/market-items/" + 다른글 + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[0])).cookie(쿠키).with(csrf()))
				.andReturn().getResponse().getStatus();
		assertThat(빈파일).isEqualTo(400);

		assertThat(checker.calls.get()).isZero();
		assertThat(신고수(id)).isZero();
	}

	@Test
	void 깨진_사진_400과_10장_꽉_참_409도_검사기보다_먼저라_검사기가_안_불린다() throws Exception {
		long id = 글올리기(쿠키);
		checker.next.set(판정(SafetyVerdict.BLOCK, SafetyCategory.ADULT, SafeSearchLikelihood.VERY_LIKELY));

		int 깨짐 = mockMvc.perform(multipart("/market-items/" + id + "/photos")
				.file(new MockMultipartFile("file", "a.jpg", "image/jpeg", "not an image".getBytes())).cookie(쿠키).with(csrf()))
				.andReturn().getResponse().getStatus();
		assertThat(깨짐).isEqualTo(400);
		assertThat(checker.calls.get()).isZero();

		for (int i = 0; i < 10; i++) {
			jdbc.update("insert into market_item_photos (market_item_id, storage_key, thumb_key, content_type, width, height, "
					+ "thumb_width, thumb_height, created_at) values (?, ?, ?, 'image/jpeg', 10, 10, 5, 5, now())", id,
					"fake/" + id + "/" + i + ".jpg", "fake/" + id + "/" + i + "_thumb.jpg");
		}
		assertThat(사진올리기(id, 쿠키).getStatus()).isEqualTo(409);
		assertThat(checker.calls.get()).isZero();
	}
}
