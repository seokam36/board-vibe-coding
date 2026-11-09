package com.example.demo.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S7-a 완료 조건 3~7: 학교·말머리 목록 공개 조회, 글 작성/조회/수정에서 school·category 동작을 API로 확인한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class SchoolCategoryApiTests {

	@Autowired MockMvc mockMvc;
	@Autowired PostRepository postRepository;
	@Autowired UserRepository userRepository;
	@Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;
	@Autowired ObjectMapper objectMapper;

	User 작성자;
	Cookie 쿠키;

	@BeforeEach
	void 준비() {
		정리();
		// S10-d: 글쓰기는 인증한 학교의 글에만 가능하므로 작성자는 경희대 인증 회원(아래 작성 테스트는 모두 경희대로 쓴다)
		작성자 = TestLogin.인증된_사용자(userRepository, jdbc, "writer", "경희대학교");
		쿠키 = TestLogin.쿠키(tokenService, 작성자);
	}

	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private JsonNode 본문(MvcResult r) throws Exception {
		return objectMapper.readTree(r.getResponse().getContentAsString());
	}

	private MvcResult 작성(String json) throws Exception {
		return mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON).content(json))
				.andReturn();
	}

	private MvcResult 수정(long id, String json) throws Exception {
		return mockMvc.perform(put("/posts/" + id).with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON).content(json))
				.andReturn();
	}

	// 실제 DB의 학교 id를 이름으로 찾는다 (id 값을 가정하지 않기 위해)
	private long 학교id(String name) throws Exception {
		for (JsonNode s : 본문(mockMvc.perform(get("/schools")).andReturn())) {
			if (s.get("name").asString().equals(name)) {
				return s.get("id").asLong();
			}
		}
		throw new AssertionError("학교 없음: " + name);
	}

	// ---------- 완료 조건 3: 공개 조회 ----------

	private void 학교_말머리_목록을_검사(Cookie c) throws Exception {
		MockHttpServletRequestBuilder schoolReq = get("/schools");
		MockHttpServletRequestBuilder catReq = get("/categories");
		if (c != null) {
			schoolReq.cookie(c);
			catReq.cookie(c);
		}
		MvcResult s = mockMvc.perform(schoolReq).andReturn();
		assertThat(s.getResponse().getStatus()).as("GET /schools").isEqualTo(200);
		List<String> names = new ArrayList<>();
		for (JsonNode n : 본문(s)) {
			assertThat(n.hasNonNull("id")).isTrue();
			names.add(n.get("name").asString());
		}
		// 이름 순서(가나다순)까지 검사한다
		assertThat(names).containsExactly("경희대학교","고려대학교", "성균관대학교", "연세대학교", "한양대학교");

		MvcResult cat = mockMvc.perform(catReq).andReturn();
		assertThat(cat.getResponse().getStatus()).as("GET /categories").isEqualTo(200);
		List<String> codes = new ArrayList<>();
		for (JsonNode n : 본문(cat)) {
			codes.add(n.get("code").asString());
			assertThat(n.get("name").asString()).as("한글 이름").isNotBlank();
		}
		assertThat(codes).containsExactlyInAnyOrder("CHAT", "QUESTION", "CAMPUS_LIFE");
	}

	@Test
	void 로그인_없이_schools와_categories를_조회하면_200과_목록을_반환한다() throws Exception {
		학교_말머리_목록을_검사(null);
	}

	@Test
	void 만료된_쿠키를_붙여도_schools와_categories는_200과_목록을_반환한다() throws Exception {
		Instant past = Instant.now().minusSeconds(3600);
		JwtClaimsSet claims = JwtClaimsSet.builder().subject("1").claim("username", "writer")
				.issuedAt(past).expiresAt(past.plusSeconds(900)).build();
		String expired = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		학교_말머리_목록을_검사(new Cookie("access_token", expired));
	}

	// ---------- 완료 조건 4: 작성 성공 ----------

	@Test
	void 올바른_schoolId와_category로_작성하면_201이고_응답의_school_category가_보낸_값과_같다() throws Exception {
		long kyunghee = 학교id("경희대학교");
		MvcResult r = 작성("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + kyunghee + ",\"category\":\"QUESTION\"}");
		assertThat(r.getResponse().getStatus()).isEqualTo(201);
		JsonNode body = 본문(r);
		assertThat(body.get("school").get("id").asLong()).isEqualTo(kyunghee);
		assertThat(body.get("school").get("name").asString()).isEqualTo("경희대학교");
		assertThat(body.get("category").get("code").asString()).isEqualTo("QUESTION");
		assertThat(body.get("category").get("name").asString()).isEqualTo("질문");
	}

	// enum에 말머리를 추가하고 DB CHECK 변경(V 파일)을 빠뜨리면 500이 나는데, 그걸 잡는 테스트
	@ParameterizedTest
	@EnumSource(PostCategory.class)
	void 모든_말머리로_글_작성이_201이고_응답_category_code가_그_값이다(PostCategory category) throws Exception {
		long school = 학교id("경희대학교");
		MvcResult r = 작성("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + school + ",\"category\":\"" + category.name() + "\"}");
		assertThat(r.getResponse().getStatus()).as(category.name()).isEqualTo(201);
		assertThat(본문(r).get("category").get("code").asString()).isEqualTo(category.name());
	}

	// ---------- 완료 조건 5: 작성 실패는 400 + 저장 안 됨 ----------

	private void 잘못된_작성은_400이고_글이_늘지_않는다(String json) throws Exception {
		long before = postRepository.count();
		assertThat(작성(json).getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(postRepository.count()).as("글 수").isEqualTo(before);
	}

	@Test
	void schoolId_누락_작성은_400이고_글이_늘지_않는다() throws Exception {
		잘못된_작성은_400이고_글이_늘지_않는다("{\"title\":\"t\",\"content\":\"c\",\"category\":\"CHAT\"}");
	}

	@Test
	void category_누락_작성은_400이고_글이_늘지_않는다() throws Exception {
		잘못된_작성은_400이고_글이_늘지_않는다("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 학교id("경희대학교") + "}");
	}

	@Test
	void 없는_schoolId_999_작성은_400이고_글이_늘지_않는다() throws Exception {
		잘못된_작성은_400이고_글이_늘지_않는다("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":999,\"category\":\"CHAT\"}");
	}

	@Test
	void 없는_category_XXX_작성은_400이고_글이_늘지_않는다() throws Exception {
		잘못된_작성은_400이고_글이_늘지_않는다(
				"{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 학교id("경희대학교") + ",\"category\":\"XXX\"}");
	}

	// ---------- 완료 조건 6: 조회 응답 ----------

	@Test
	void 목록과_단건_조회_응답에_school_category가_있고_옛_글의_school은_null이다() throws Exception {
		long kyunghee = 학교id("경희대학교");
		long newId = 본문(작성("{\"title\":\"새글\",\"content\":\"c\",\"schoolId\":" + kyunghee +",\"category\":\"CAMPUS_LIFE\"}"))
				.get("id").asLong();
		// 옛 글 흉내: 3개짜리 생성자는 school 없음 + CHAT (V2가 채우는 기존 글과 같은 모양)
		long oldId = postRepository.save(new Post("옛글", "c", 작성자, null, PostCategory.CHAT)).getId();

		// 단건
		JsonNode one = 본문(mockMvc.perform(get("/posts/" + newId)).andReturn());
		assertThat(one.get("school").get("name").asString()).isEqualTo("경희대학교");
		assertThat(one.get("category").get("code").asString()).isEqualTo("CAMPUS_LIFE");
		JsonNode oldOne = 본문(mockMvc.perform(get("/posts/" + oldId)).andReturn());
		assertThat(oldOne.has("school")).as("school 키 존재").isTrue();
		assertThat(oldOne.get("school").isNull()).as("옛 글 school은 null").isTrue();
		assertThat(oldOne.get("category").get("code").asString()).isEqualTo("CHAT");

		// 목록
		MvcResult list = mockMvc.perform(get("/posts")).andReturn();
		assertThat(list.getResponse().getStatus()).isEqualTo(200);
		int checked = 0;
		for (JsonNode p : 본문(list)) {
			assertThat(p.has("school")).as("목록 항목에 school 키").isTrue();
			assertThat(p.hasNonNull("category")).as("목록 항목에 category").isTrue();
			if (p.get("id").asLong() == oldId) {
				assertThat(p.get("school").isNull()).isTrue();
				checked++;
			}
			if (p.get("id").asLong() == newId) {
				assertThat(p.get("school").get("name").asString()).isEqualTo("경희대학교");
				checked++;
			}
		}
		assertThat(checked).as("목록에서 확인한 글 수").isEqualTo(2);
	}

	// ---------- 완료 조건 7: 수정 ----------

	@Test
	void 수정에_새_category를_보내면_말머리가_바뀌고_다른_schoolId를_보내도_학교는_그대로다() throws Exception {
		long kyunghee = 학교id("경희대학교");
		long hanyang = 학교id("한양대학교");
		long id = 본문(작성("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + kyunghee + ",\"category\":\"CHAT\"}"))
				.get("id").asLong();

		MvcResult r = 수정(id, "{\"title\":\"t2\",\"content\":\"c2\",\"category\":\"QUESTION\",\"schoolId\":" + hanyang + "}");
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		assertThat(본문(r).get("category").get("code").asString()).isEqualTo("QUESTION");

		// DB에 실제로 반영됐는지 GET으로 확인
		JsonNode after = 본문(mockMvc.perform(get("/posts/" + id)).andReturn());
		assertThat(after.get("category").get("code").asString()).isEqualTo("QUESTION");
		assertThat(after.get("school").get("id").asLong()).as("학교는 그대로").isEqualTo(kyunghee);
	}

	@Test
	void 수정에서_category_누락은_400이고_원본이_유지된다() throws Exception {
		수정이_400이고_원본이_유지되는지("{\"title\":\"t2\",\"content\":\"c2\"}");
	}

	@Test
	void 수정에서_잘못된_category는_400이고_원본이_유지된다() throws Exception {
		수정이_400이고_원본이_유지되는지("{\"title\":\"t2\",\"content\":\"c2\",\"category\":\"XXX\"}");
	}

	private void 수정이_400이고_원본이_유지되는지(String json) throws Exception {
		long id = 본문(작성("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":" + 학교id("경희대학교") + ",\"category\":\"CHAT\"}"))
				.get("id").asLong();
		assertThat(수정(id, json).getResponse().getStatus()).as(json).isEqualTo(400);
		JsonNode after = 본문(mockMvc.perform(get("/posts/" + id)).andReturn());
		assertThat(after.get("title").asString()).isEqualTo("t");
		assertThat(after.get("category").get("code").asString()).isEqualTo("CHAT");
	}
}
