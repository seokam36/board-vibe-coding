package com.example.demo.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

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

// S13-f 완료 조건 검사: GET /market-items?q= 제목 검색
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MarketItemSearchTests {

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired ObjectMapper om;
	@Autowired EntityManagerFactory emf;

	User 회원; // 연세대 인증
	User 남; // 고려대 인증
	Cookie 쿠키;
	Cookie 남쿠키;

	@BeforeEach
	void 준비() {
		정리();
		회원 = TestLogin.인증된_사용자(userRepository, jdbc, "suser", "연세대학교");
		남 = TestLogin.인증된_사용자(userRepository, jdbc, "sother", "고려대학교");
		쿠키 = TestLogin.쿠키(tokenService, 회원);
		남쿠키 = TestLogin.쿠키(tokenService, 남);
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from market_items");
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	// 글 올리기: JSON은 ObjectMapper로 만들어 역슬래시·따옴표도 안전하게 보낸다
	private long 올리기(Cookie c, String title, String content, String tradeType, Integer price) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("title", title);
		body.put("content", content);
		body.put("tradeType", tradeType);
		if (price != null) {
			body.put("price", price);
		}
		MockHttpServletResponse r = mockMvc.perform(post("/market-items").contentType(MediaType.APPLICATION_JSON)
				.content(om.writeValueAsString(body)).cookie(c).with(csrf())).andReturn().getResponse();
		assertThat(r.getStatus()).as("준비용 글 올리기: " + title).isEqualTo(201);
		return om.readTree(r.getContentAsString()).get("id").asLong();
	}

	private long 판매글(String title) throws Exception {
		return 올리기(쿠키, title, "내용", "SALE", 1000);
	}

	private long 학교id(String name) {
		return jdbc.queryForObject("select id from schools where name = ?", Long.class, name);
	}

	// 쿼리 문자열 대신 param으로 보내 인코딩 문제 없이 q를 그대로 전달한다. kv는 이름,값,이름,값 ...
	private MockHttpServletResponse 목록(String... kv) throws Exception {
		MockHttpServletRequestBuilder b = get("/market-items");
		for (int i = 0; i < kv.length; i += 2) {
			b.param(kv[i], kv[i + 1]);
		}
		return mockMvc.perform(b).andReturn().getResponse();
	}

	private List<Long> 검색ids(String q) throws Exception {
		return ids(목록("q", q));
	}

	private List<Long> ids(MockHttpServletResponse r) throws Exception {
		assertThat(r.getStatus()).isEqualTo(200);
		JsonNode arr = om.readTree(r.getContentAsString());
		assertThat(arr.isArray()).isTrue();
		List<Long> ids = new ArrayList<>();
		for (JsonNode n : arr) {
			ids.add(n.get("id").asLong());
		}
		return ids;
	}

	// ============ 조건 1: 부분 일치, 제목만 ============

	@Test
	void 조건1_제목에_검색어가_들어_있는_글만_찾는다() throws Exception {
		long a = 판매글("중고 자전거 팝니다");
		long b = 판매글("자전거 헬멧");
		판매글("노트북 팝니다");
		assertThat(검색ids("자전거")).containsExactly(b, a);
	}

	@Test
	void 조건1_본문에만_있는_단어는_걸리지_않는다() throws Exception {
		long 제목글 = 올리기(쿠키, "책상 팝니다", "자전거는 아닙니다", "SALE", 1000);
		assertThat(검색ids("자전거")).isEmpty();
		assertThat(검색ids("책상")).containsExactly(제목글);
	}

	@Test
	void 조건1_일치하는_글이_없으면_빈_배열이다() throws Exception {
		판매글("노트북");
		MockHttpServletResponse r = 목록("q", "자전거");
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(r.getContentAsString()).isEqualTo("[]");
	}

	// ============ 조건 2: 대소문자 무시 ============

	@Test
	void 조건2_대소문자를_구분하지_않는다() throws Exception {
		long a = 판매글("iPad 팝니다");
		판매글("갤럭시 탭");
		assertThat(검색ids("ipad")).containsExactly(a);
		assertThat(검색ids("IPAD")).containsExactly(a);
		assertThat(검색ids("iPad")).containsExactly(a);
	}

	// ============ 조건 3: 와일드카드 글자 그대로 ============

	@Test
	void 조건3_퍼센트는_글자_그대로_찾는다() throws Exception {
		long a = 판매글("50% 할인");
		판매글("500원 균일가");
		판매글("5000 원");
		assertThat(검색ids("50%")).containsExactly(a);
	}

	@Test
	void 조건3_밑줄은_글자_그대로_찾는다() throws Exception {
		long a = 판매글("a_b 세트");
		판매글("axb 세트");
		assertThat(검색ids("a_b")).containsExactly(a);
	}

	@Test
	void 조건3_역슬래시가_들어간_검색어도_500_없이_글자_그대로_찾는다() throws Exception {
		long a = 판매글("경로 c:\\temp 파일");
		long b = 판매글("끝에 역슬래시\\");
		판매글("일반 글");
		assertThat(검색ids("c:\\temp")).containsExactly(a);
		assertThat(검색ids("\\")).containsExactly(b, a);
		// 역슬래시 뒤에 와일드카드가 붙은 조합도 에러 없이 처리
		assertThat(목록("q", "\\%").getStatus()).isEqualTo(200);
		assertThat(목록("q", "\\_").getStatus()).isEqualTo(200);
		assertThat(검색ids("\\%")).isEmpty();
	}

	// ============ 조건 4: 공백 ============

	@Test
	void 조건4_앞뒤_공백은_잘라서_검색한다() throws Exception {
		long a = 판매글("중고 자전거");
		판매글("노트북");
		assertThat(검색ids("  자전거  ")).containsExactly(a);
		assertThat(검색ids("  자전거  ")).isEqualTo(검색ids("자전거"));
	}

	@Test
	void 조건4_공백만_있거나_빈_값이면_전체_목록과_같다() throws Exception {
		판매글("하나");
		판매글("둘");
		List<Long> 전체 = ids(목록());
		assertThat(전체).hasSize(2);
		assertThat(검색ids("   ")).isEqualTo(전체);
		assertThat(검색ids("")).isEqualTo(전체);
	}

	// ============ 조건 5: 50자 경계 ============

	@Test
	void 조건5_50자는_200이고_51자는_400이다() throws Exception {
		long a = 판매글("가".repeat(50));
		assertThat(검색ids("가".repeat(50))).containsExactly(a);
		// 서버는 에러 message를 응답 본문에 싣지 않는다(프로젝트 설정). 대신 던져진 예외의 사유로 문구를 확인한다
		var result = mockMvc.perform(get("/market-items").param("q", "가".repeat(51))).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(result.getResolvedException()).isNotNull();
		assertThat(result.getResolvedException().getMessage()).contains("검색어는 50자 이하여야 합니다.");
	}

	@Test
	void 조건5_이모지_50개는_코드포인트_50자라서_200이다() throws Exception {
		String 이모지50 = "😀".repeat(50);
		assertThat(이모지50.length()).isEqualTo(100); // 자바 길이는 100이지만 글자 수는 50
		long a = 판매글(이모지50);
		assertThat(검색ids(이모지50)).containsExactly(a);
		assertThat(목록("q", "😀".repeat(51)).getStatus()).isEqualTo(400);
	}

	@Test
	void 조건5_앞뒤_공백_포함_51자_이상이어도_자른_뒤_50자면_200이다() throws Exception {
		long a = 판매글("나".repeat(50));
		assertThat(검색ids(" " + "나".repeat(50) + " ")).containsExactly(a);
		assertThat(검색ids("   " + "나".repeat(50) + "   ")).containsExactly(a);
		// 자른 뒤 51자면 여전히 400
		assertThat(목록("q", " " + "나".repeat(51) + " ").getStatus()).isEqualTo(400);
	}

	// ============ 조건 6: 기존 필터와 AND ============

	@Test
	void 조건6_q와_학교_종류_상태를_모두_만족하는_글만_준다() throws Exception {
		long 목표 = 올리기(쿠키, "자전거 목표", "내용", "SALE", 1000);
		올리기(쿠키, "자전거 나눔", "내용", "FREE", null); // 종류 다름
		long 완료 = 올리기(쿠키, "자전거 완료", "내용", "SALE", 1000); // 상태 다름
		jdbc.update("update market_items set status = 'DONE' where id = ?", 완료);
		올리기(남쿠키, "자전거 고려", "내용", "SALE", 1000); // 학교 다름
		올리기(쿠키, "노트북", "내용", "SALE", 1000); // 제목 다름
		MockHttpServletResponse r = 목록("q", "자전거", "schoolId", String.valueOf(학교id("연세대학교")),
				"tradeType", "SALE", "status", "ON_SALE");
		assertThat(ids(r)).containsExactly(목표);
		// q만 쓰면 자전거 글 4개
		assertThat(검색ids("자전거")).hasSize(4);
	}

	@Test
	void 조건6_q가_너무_길고_학교도_없으면_404가_아니라_400이다() throws Exception {
		판매글("책상");
		MockHttpServletResponse r = 목록("q", "가".repeat(51), "schoolId", "999999999");
		assertThat(r.getStatus()).isEqualTo(400);
		// q가 정상이면 없는 학교는 여전히 404
		assertThat(목록("q", "책상", "schoolId", "999999999").getStatus()).isEqualTo(404);
	}

	// ============ 조건 7: 정렬·쿼리 수 ============

	@Test
	void 조건7_검색_결과도_id_내림차순이다() throws Exception {
		long a = 판매글("자전거 1");
		판매글("다른 글");
		long c = 판매글("자전거 2");
		long d = 올리기(남쿠키, "자전거 3", "내용", "FREE", null);
		assertThat(검색ids("자전거")).containsExactly(d, c, a);
	}

	private long 검색_쿼리수(String q) throws Exception {
		Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			stats.clear();
			assertThat(검색ids(q)).isNotEmpty();
			return stats.getPrepareStatementCount();
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}

	@Test
	void 조건7_검색_쿼리_수는_결과_수와_무관하다() throws Exception {
		판매글("자전거 하나");
		long 하나일때 = 검색_쿼리수("자전거");
		올리기(남쿠키, "자전거 둘", "내용", "SALE", 1000);
		올리기(쿠키, "자전거 셋", "내용", "FREE", null);
		올리기(남쿠키, "자전거 넷", "내용", "SALE", 1000);
		올리기(쿠키, "자전거 다섯", "내용", "SALE", 1000);
		long 다섯일때 = 검색_쿼리수("자전거");
		assertThat(다섯일때).as("결과 1개 vs 5개 쿼리 수").isEqualTo(하나일때);
		assertThat(하나일때).isLessThanOrEqualTo(2);
	}

	// ============ review 수정분: NUL, 대소문자 변환, SQL 주입 ============

	// 에러 사유(예외 메시지)까지 확인하는 도우미
	private void 와_400_메시지(String q, String schoolId, String 메시지) throws Exception {
		MockHttpServletRequestBuilder b = get("/market-items").param("q", q);
		if (schoolId != null) {
			b.param("schoolId", schoolId);
		}
		var result = mockMvc.perform(b).andReturn();
		assertThat(result.getResponse().getStatus()).isEqualTo(400);
		assertThat(result.getResolvedException()).isNotNull();
		assertThat(result.getResolvedException().getMessage()).contains(메시지);
	}

	@Test
	void NUL_한_글자만_있는_검색어는_400이고_사유가_안내된다() throws Exception {
		판매글("책상");
		와_400_메시지("\u0000", null, "검색어에 쓸 수 없는 문자가 있습니다.");
	}

	@Test
	void 글자_사이에_NUL이_끼면_400이고_사유가_안내된다() throws Exception {
		판매글("ab 책상");
		와_400_메시지("a\u0000b", null, "검색어에 쓸 수 없는 문자가 있습니다.");
	}

	@Test
	void NUL이_든_검색어는_없는_학교여도_404가_아니라_400이다() throws Exception {
		와_400_메시지("a\u0000b", "999999999", "검색어에 쓸 수 없는 문자가 있습니다.");
	}

	@Test
	void 대소문자_변환을_DB가_양쪽에_해서_점_있는_I도_같은_글자로_찾는다() throws Exception {
		long a = 판매글("İstanbul 여행 가방");
		판매글("다른 글");
		assertThat(검색ids("İstanbul")).containsExactly(a);
		assertThat(검색ids("İSTANBUL")).containsExactly(a);
		// 기존 영문 대소문자 무시도 그대로
		long b = 판매글("iPad 미니");
		assertThat(검색ids("IPAD")).containsExactly(b);
	}

	@Test
	void 소문자_istanbul_검색은_DB_로케일에_따라_달라서_500_없이_200만_확인한다() throws Exception {
		판매글("İstanbul 여행 가방");
		// 찾히는지는 PostgreSQL 로케일에 달려 있어 결과는 단언하지 않는다
		assertThat(목록("q", "istanbul").getStatus()).isEqualTo(200);
	}

	@Test
	void 작은따옴표가_든_검색어도_200이고_엉뚱한_글이_섞이지_않으며_표가_그대로다() throws Exception {
		long 따옴표글 = 판매글("a' or '1'='1 특가");
		long 다른글 = 판매글("노트북");
		int 전 = jdbc.queryForObject("select count(*) from market_items", Integer.class);
		// 주입이 먹혔다면 전체 글이 나온다. 글자 그대로만 찾아야 한다
		assertThat(검색ids("a' or '1'='1")).containsExactly(따옴표글);
		assertThat(검색ids("'); drop table market_items; --")).isEmpty();
		assertThat(검색ids("' or 1=1 --")).isEmpty();
		int 후 = jdbc.queryForObject("select count(*) from market_items", Integer.class);
		assertThat(후).isEqualTo(전).isEqualTo(2);
		assertThat(검색ids("노트북")).containsExactly(다른글);
	}

	// ============ 조건 8: 공개 접근 ============

	@Test
	void 조건8_비로그인도_검색이_200이다() throws Exception {
		long a = 판매글("공개 자전거");
		MockHttpServletResponse r = mockMvc.perform(get("/market-items").param("q", "자전거")).andReturn().getResponse();
		assertThat(r.getStatus()).isEqualTo(200);
		assertThat(om.readTree(r.getContentAsString()).get(0).get("id").asLong()).isEqualTo(a);
	}
}
