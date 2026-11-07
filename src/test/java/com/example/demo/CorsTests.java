package com.example.demo;

import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class CorsTests {

	static final String ALLOWED = "http://localhost:5173";
	static final String EVIL = "http://evil.example.com";

	@Autowired
	MockMvc mockMvc;

	@Autowired
	PostRepository postRepository;

	@Autowired
	UserRepository userRepository;

	@Autowired
	TokenService tokenService;

	// 글쓰기(POST)에 쓰는 로그인 쿠키
	Cookie 쿠키;

	// 글쓴이로 쓸 사용자를 만들고 로그인 쿠키를 준비한다
	@BeforeEach
	void 준비() {
		정리();
		쿠키 = TestLogin.쿠키(tokenService, TestLogin.사용자(userRepository, "writer"));
	}

	// POST 테스트로 생긴 데이터를 정리한다 (글이 사용자를 참조하므로 글부터)
	@AfterEach
	void 정리() {
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	@Test
	void 허용된_origin의_preflight는_200과_허용_헤더를_반환한다() throws Exception {
		mockMvc.perform(options("/posts")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "Content-Type"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Methods", containsString("POST")));
	}

	@Test
	void 허용된_origin의_GET은_200과_Allow_Origin을_반환한다() throws Exception {
		mockMvc.perform(get("/posts").header("Origin", ALLOWED))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
	}

	@Test
	void 허용된_origin의_POST는_201과_Allow_Origin을_반환한다() throws Exception {
		mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키)
						.header("Origin", ALLOWED)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"t\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
	}

	// 에러 응답에도 CORS 헤더가 붙어야 프론트가 상태코드를 읽을 수 있다
	@Test
	void 허용된_origin의_빈_title_POST는_400과_Allow_Origin을_반환한다() throws Exception {
		mockMvc.perform(post("/posts").with(csrf()).cookie(쿠키)
						.header("Origin", ALLOWED)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"\",\"content\":\"c\",\"schoolId\":1,\"category\":\"CHAT\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
	}

	@Test
	void 허용된_origin의_없는_게시글_조회는_404와_Allow_Origin을_반환한다() throws Exception {
		mockMvc.perform(get("/posts/999999").header("Origin", ALLOWED))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED));
	}

	@Test
	void 허용되지_않은_origin의_preflight는_403이고_Allow_Origin이_없다() throws Exception {
		mockMvc.perform(options("/posts")
						.header("Origin", EVIL)
						.header("Access-Control-Request-Method", "POST")
						.header("Access-Control-Request-Headers", "Content-Type"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	@Test
	void 허용되지_않은_origin의_GET은_Allow_Origin이_없다() throws Exception {
		mockMvc.perform(get("/posts").header("Origin", EVIL))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
	}

	// S2-a 조건 7: PUT preflight가 허용되고 Allow-Methods에 PUT이 포함된다
	@Test
	void 허용된_origin의_PUT_preflight는_200과_PUT_허용을_반환한다() throws Exception {
		mockMvc.perform(options("/posts/1")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "PUT")
						.header("Access-Control-Request-Headers", "Content-Type"))
				.andExpect(status().isOk())
				// Allow-Origin이 없으면 브라우저가 실제 요청을 막는다
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Methods", containsString("PUT")));
	}

	// S2-a 조건 7: DELETE preflight가 허용되고 Allow-Methods에 DELETE가 포함된다
	@Test
	void 허용된_origin의_DELETE_preflight는_200과_DELETE_허용을_반환한다() throws Exception {
		mockMvc.perform(options("/posts/1")
						.header("Origin", ALLOWED)
						.header("Access-Control-Request-Method", "DELETE"))
				.andExpect(status().isOk())
				// Allow-Origin이 없으면 브라우저가 실제 요청을 막는다
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED))
				.andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")));
	}
}
