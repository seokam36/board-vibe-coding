package com.example.demo.post;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class) // 테스트용 PostgreSQL 컨테이너 사용
@AutoConfigureMockMvc
class PostApiTests {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PostRepository postRepository;

	@Autowired
	UserRepository userRepository;

	@Autowired
	TokenService tokenService;

	// 글쓰기에 쓰는 로그인 쿠키 (글쓴이 = "writer")
	Cookie 쿠키;

	@Autowired
	ObjectMapper objectMapper; // 스프링이 등록한 Jackson 3 매퍼 주입

	// 테스트 간 데이터가 섞이지 않게 정리하고, 글쓴이로 쓸 사용자를 만든다
	@BeforeEach
	void 준비() {
		정리();
		쿠키 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "writer"));
	}

	@AfterEach
	void 정리() {
		// 글이 사용자를 참조(외래키)하므로 글부터 지운다
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	private MvcResult 작성(String json) throws Exception {
		return mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
	}

	private JsonNode 본문(MvcResult r) throws Exception {
		return objectMapper.readTree(r.getResponse().getContentAsString());
	}

	@Test
	void 작성시_201과_필드_및_Location을_반환한다() throws Exception {
		MvcResult r = 작성("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
		assertThat(r.getResponse().getStatus()).isEqualTo(201);
		JsonNode body = 본문(r);
		assertThat(body.hasNonNull("id")).isTrue();
		assertThat(body.get("title").asString()).isEqualTo("t");
		assertThat(body.get("content").asString()).isEqualTo("c");
		assertThat(body.hasNonNull("createdAt")).isTrue();
		assertThat(r.getResponse().getHeader("Location")).isEqualTo("/posts/" + body.get("id").asLong());
	}

	@Test
	void 작성한_글을_단건_조회하면_200과_같은_내용을_반환한다() throws Exception {
		long id = 본문(작성("{\"title\":\"제목\",\"content\":\"내용\",\"schoolId\":1,\"category\":\"CHAT\"}")).get("id").asLong();
		MvcResult r = mockMvc.perform(get("/posts/" + id)).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = 본문(r);
		assertThat(body.get("id").asLong()).isEqualTo(id);
		assertThat(body.get("title").asString()).isEqualTo("제목");
		assertThat(body.get("content").asString()).isEqualTo("내용");
	}

	// 작성 응답의 createdAt과 단건 조회의 createdAt이 같고, UTC("Z") ISO-8601 형식이어야 한다
	@Test
	void 작성_응답과_단건_조회의_createdAt이_같고_UTC_형식이다() throws Exception {
		JsonNode created = 본문(작성("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}"));
		String createdAt = created.get("createdAt").asString();
		MvcResult r = mockMvc.perform(get("/posts/" + created.get("id").asLong())).andReturn();
		assertThat(본문(r).get("createdAt").asString()).isEqualTo(createdAt);
		assertThat(createdAt).endsWith("Z");
		assertThat(java.time.Instant.parse(createdAt)).isNotNull();
	}

	@Test
	void 목록_조회는_200과_길이_2이고_최신순이다() throws Exception {
		작성("{\"title\":\"first\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
		작성("{\"title\":\"second\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
		MvcResult r = mockMvc.perform(get("/posts")).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(200);
		JsonNode body = 본문(r);
		assertThat(body.size()).isEqualTo(2);
		assertThat(body.get(0).get("title").asString()).isEqualTo("second");
		assertThat(body.get(1).get("title").asString()).isEqualTo("first");
	}

	@Test
	void 없는_게시글_조회시_404를_반환한다() throws Exception {
		MvcResult r = mockMvc.perform(get("/posts/999999")).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(404);
	}

	@Test
	void 숫자가_아닌_id_조회시_400을_반환한다() throws Exception {
		MvcResult r = mockMvc.perform(get("/posts/abc")).andReturn();
		assertThat(r.getResponse().getStatus()).isEqualTo(400);
	}

	// 잘못된 입력은 400이고 DB 게시글 수가 늘지 않아야 한다
	private void 잘못된_입력은_400이고_저장되지_않는다(String json) throws Exception {
		long before = postRepository.count();
		MvcResult r = 작성(json);
		assertThat(r.getResponse().getStatus()).as(json).isEqualTo(400);
		assertThat(postRepository.count()).as(json).isEqualTo(before);
	}

	@Test
	void title이_빈문자열이면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":\"\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
	}

	@Test
	void title이_누락되면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
	}

	@Test
	void title이_공백뿐이면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":\"   \",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
	}

	@Test
	void content가_빈문자열이면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":\"t\",\"content\":\"\",\"schoolId\":1,\"category\":\"CHAT\"}");
	}

	@Test
	void content가_누락되면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":\"t\"}");
	}

	@Test
	void content가_공백뿐이면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":\"t\",\"content\":\"   \",\"schoolId\":1,\"category\":\"CHAT\"}");
	}

	@Test
	void title이_101자면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":\"" + "a".repeat(101) + "\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
	}

	@Test
	void title이_100자면_201로_저장된다() throws Exception {
		MvcResult r = 작성("{\"title\":\"" + "a".repeat(100) + "\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
		assertThat(r.getResponse().getStatus()).isEqualTo(201);
		assertThat(postRepository.count()).isEqualTo(1);
	}

	// 깨진 JSON 본문은 400이고 저장되지 않는다
	@Test
	void 깨진_JSON_본문이면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":");
	}

	// NUL 문자(\u0000)는 title/content 모두 400
	@Test
	void title에_NUL_문자가_있으면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":\"a\u0000b\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}");
	}

	@Test
	void content에_NUL_문자가_있으면_400이고_저장되지_않는다() throws Exception {
		잘못된_입력은_400이고_저장되지_않는다("{\"title\":\"t\",\"content\":\"a\u0000b\",\"schoolId\":1,\"category\":\"CHAT\"}");
	}
}
