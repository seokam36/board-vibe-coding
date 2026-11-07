package com.example.demo.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.school.domain.School;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S7-b 완료 조건: GET /posts?schoolId=&category= 거르기 (최신순, AND 조합, 404/400, N+1 없음)
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PostFilterApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired PostRepository postRepository;
	@Autowired UserRepository userRepository;
	@Autowired SchoolRepository schoolRepository;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired ObjectMapper objectMapper;
	@Autowired EntityManagerFactory entityManagerFactory;

	User 작성자;
	long 연세, 고려, 한양;
	// 학교별/말머리별로 만든 글 id (최신순 검증용)
	long 연세질문1, 연세잡담, 연세질문2, 고려질문, 옛글;

	@BeforeEach
	void 준비() throws Exception {
		정리();
		작성자 = TestLogin.사용자(userRepository, "writer");
		연세 = 학교id("연세대학교");
		고려 = 학교id("고려대학교");
		한양 = 학교id("한양대학교");
		// 저장 순서 = id 순서. 최신순이면 이 역순으로 나와야 한다
		옛글 = 저장("옛글", 작성자, null, PostCategory.QUESTION);
		연세질문1 = 저장("연세질문1", 작성자, 연세, PostCategory.QUESTION);
		연세잡담 = 저장("연세잡담", 작성자, 연세, PostCategory.CHAT);
		고려질문 = 저장("고려질문", 작성자, 고려, PostCategory.QUESTION);
		연세질문2 = 저장("연세질문2", 작성자, 연세, PostCategory.QUESTION);
	}

	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private long 저장(String title, User author, Long schoolId, PostCategory category) {
		School school = schoolId == null ? null : schoolRepository.findById(schoolId).orElseThrow();
		return postRepository.save(new Post(title, "본문", author, school, category)).getId();
	}

	private JsonNode 본문(MvcResult r) throws Exception {
		return objectMapper.readTree(r.getResponse().getContentAsString());
	}

	// 학교 id를 /schools 응답에서 이름으로 찾는다
	private long 학교id(String name) throws Exception {
		for (JsonNode s : 본문(mockMvc.perform(get("/schools")).andReturn())) {
			if (s.get("name").asString().equals(name)) {
				return s.get("id").asLong();
			}
		}
		throw new AssertionError("학교 없음: " + name);
	}

	private List<Long> id목록(String query) throws Exception {
		MvcResult r = mockMvc.perform(get("/posts" + query)).andReturn();
		assertThat(r.getResponse().getStatus()).as(query).isEqualTo(200);
		List<Long> ids = new ArrayList<>();
		for (JsonNode p : 본문(r)) {
			ids.add(p.get("id").asLong());
		}
		return ids;
	}

	private int 상태(String query) throws Exception {
		return mockMvc.perform(get("/posts" + query)).andReturn().getResponse().getStatus();
	}

	// 조건 1
	@Test
	void 학교로_거르면_그_학교_글만_최신순으로_나오고_옛_글은_빠진다() throws Exception {
		assertThat(id목록("?schoolId=" + 연세)).containsExactly(연세질문2, 연세잡담, 연세질문1);
	}

	// 조건 2
	@Test
	void 말머리_QUESTION으로_거르면_질문_글만_최신순으로_나온다() throws Exception {
		assertThat(id목록("?category=QUESTION")).containsExactly(연세질문2, 고려질문, 연세질문1, 옛글);
	}

	// 조건 3
	@Test
	void 학교와_말머리를_같이_주면_두_조건이_모두_맞는_글만_나온다() throws Exception {
		assertThat(id목록("?schoolId=" + 연세 + "&category=QUESTION")).containsExactly(연세질문2, 연세질문1);
	}

	// 조건 4
	@Test
	void 값_없이_조회하면_학교_없는_옛_글까지_전체가_최신순으로_나온다() throws Exception {
		assertThat(id목록("")).containsExactly(연세질문2, 고려질문, 연세잡담, 연세질문1, 옛글);
	}

	// 화면이 "전체"를 빈 값으로 보내도 조건 없는 조회와 같아야 한다 (옛 글 포함, 최신순)
	@Test
	void 학교와_말머리가_빈_값이면_조건_없는_조회처럼_옛_글까지_전체가_최신순으로_나온다() throws Exception {
		assertThat(id목록("?schoolId=&category=")).containsExactly(연세질문2, 고려질문, 연세잡담, 연세질문1, 옛글);
	}

	// 조건 5
	@Test
	void 글이_없는_학교를_고르면_200과_빈_배열을_반환한다() throws Exception {
		MvcResult r = mockMvc.perform(get("/posts?schoolId=" + 한양)).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(r.getResponse().getContentAsString().trim()).isEqualTo("[]");
	}

	// 조건 6
	@Test
	void 없는_학교_999는_404를_반환한다() throws Exception {
		assertThat(상태("?schoolId=999")).isEqualTo(404);
	}

	@Test
	void schoolId가_숫자가_아니면_400을_반환한다() throws Exception {
		assertThat(상태("?schoolId=abc")).isEqualTo(400);
	}

	@Test
	void 없는_말머리_XXX는_400을_반환한다() throws Exception {
		assertThat(상태("?category=XXX")).isEqualTo(400);
	}

	// 조건 7
	@Test
	void 만료된_쿠키를_붙여도_거르기_결과가_같다() throws Exception {
		Instant past = Instant.now().minusSeconds(3600);
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("username", "writer")
				.issuedAt(past).expiresAt(past.plusSeconds(900)).build();
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		MvcResult r = mockMvc.perform(get("/posts?schoolId=" + 연세 + "&category=QUESTION")
				.cookie(new Cookie("access_token", expired))).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		List<Long> ids = new ArrayList<>();
		for (JsonNode p : 본문(r)) {
			ids.add(p.get("id").asLong());
		}
		assertThat(ids).containsExactly(연세질문2, 연세질문1);
	}

	// 조건 8: 글 1개일 때와 여러 개(작성자·학교 제각각)일 때 쿼리 수가 같아야 한다
	@Test
	void 거르기_조회의_쿼리_수는_글_수에_비례하지_않는다() throws Exception {
		Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		try {
			List<Long> counts = new ArrayList<>();
			long[] schools = { 연세, 고려, 한양 };
			for (int n : new int[] { 1, 6 }) {
				postRepository.deleteAll();
				userRepository.deleteAll();
				for (int i = 0; i < n; i++) {
					User u = TestLogin.사용자(userRepository, "w" + i);
					저장("제목" + i, u, schools[i % 3], PostCategory.QUESTION);
				}
				stats.clear();
				// schoolId 없이 category만 걸어 여러 학교 글이 한 번에 나오게 한다
				List<Long> ids = id목록("?category=QUESTION");
				assertThat(ids).hasSize(n);
				counts.add(stats.getPrepareStatementCount());
			}
			assertThat(counts.get(1)).as("글 6개 쿼리 수(%s)는 글 1개 쿼리 수(%s)와 같아야 한다", counts.get(1), counts.get(0))
					.isEqualTo(counts.get(0));
		} finally {
			stats.setStatisticsEnabled(false);
		}
	}
}
