package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S5-a 닉네임 기능의 완료 조건(가입 검증·중복·동시 가입·응답 필드·/auth/me DB 확인·작성자 표시·DB 제약)을 검사한다
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class NicknameTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	UserRepository userRepository;
	@Autowired
	PostRepository postRepository;
	@Autowired
	JwtEncoder jwtEncoder;

	private final ObjectMapper om = new ObjectMapper();

	@AfterEach
	void 정리() {
		// 글이 사용자를 참조(외래키)하므로 글부터 지운다
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	// nicknameJson: JSON 값 그대로(따옴표 포함). null이면 nickname 필드 자체를 빼고 보낸다
	private ResultActions 가입(String username, String nicknameJson) throws Exception {
		String nick = nicknameJson == null ? "" : ",\"nickname\":" + nicknameJson;
		return mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\"" + nick + ",\"password\":\"password123\"}"));
	}

	private ResultActions 닉네임가입(String username, String nickname) throws Exception {
		return 가입(username, "\"" + nickname + "\"");
	}

	private MockHttpServletResponse 로그인(String username) throws Exception {
		return mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"password123\"}")).andReturn().getResponse();
	}

	// ---------- 조건 1: 가입 응답 ----------

	@Test
	void 조건1_가입하면_201과_id_username_nickname을_반환하고_비밀번호는_없다() throws Exception {
		String body = 닉네임가입("user01", "홍길동").andExpect(status().isCreated())
				.andExpect(jsonPath("$.username").value("user01"))
				.andExpect(jsonPath("$.nickname").value("홍길동"))
				.andExpect(jsonPath("$.id").isNumber())
				.andReturn().getResponse().getContentAsString();
		assertThat(body.toLowerCase()).doesNotContain("password");
		assertThat(userRepository.findByUsername("user01").orElseThrow().getNickname()).isEqualTo("홍길동");
	}

	// ---------- 조건 2: 닉네임 형식 ----------

	@Test
	void 조건2_nickname_필드가_없으면_400() throws Exception {
		가입("user01", null).andExpect(status().isBadRequest());
		assertThat(userRepository.count()).isZero();
	}

	@Test
	void 조건2_nickname이_null이면_400() throws Exception {
		가입("user01", "null").andExpect(status().isBadRequest());
		assertThat(userRepository.count()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "a", "가", "012345678901234567890", "홍 길동", "kim!", "a b", "홍길동#", "kim-lee", "김.철" })
	void 조건2_잘못된_nickname은_400이고_저장되지_않는다(String nickname) throws Exception {
		닉네임가입("user01", nickname).andExpect(status().isBadRequest());
		assertThat(userRepository.count()).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ab", "가나", "12", "__", "01234567890123456789", "가나다라마바사아자차카타파하가나다라마바", "홍길동", "Kim_01", "a_가1" })
	void 조건2_경계값과_허용_문자_조합은_201(String nickname) throws Exception {
		닉네임가입("user01", nickname).andExpect(status().isCreated())
				.andExpect(jsonPath("$.nickname").value(nickname));
	}

	// ---------- 조건 3: 중복 ----------

	@Test
	void 조건3_이미_있는_nickname으로_가입하면_409이고_행이_늘지_않는다() throws Exception {
		닉네임가입("user01", "홍길동").andExpect(status().isCreated());
		닉네임가입("user02", "홍길동").andExpect(status().isConflict());
		assertThat(userRepository.count()).isEqualTo(1);
	}

	@Test
	void 조건3_대소문자만_다른_nickname은_서로_다른_닉네임이라_201() throws Exception {
		닉네임가입("user01", "Kim").andExpect(status().isCreated());
		닉네임가입("user02", "kim").andExpect(status().isCreated());
		assertThat(userRepository.count()).isEqualTo(2);
	}

	// ---------- 조건 4: 동시 가입 ----------

	@Test
	void 조건4_같은_nickname으로_동시에_가입하면_한_명만_201이고_나머지는_409() throws Exception {
		int n = 6;
		ExecutorService pool = Executors.newFixedThreadPool(n);
		CountDownLatch ready = new CountDownLatch(n);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			String username = "racer" + i;
			results.add(pool.submit(() -> {
				ready.countDown();
				go.await();
				return 닉네임가입(username, "같은닉네임").andReturn().getResponse().getStatus();
			}));
		}
		ready.await();
		go.countDown();
		int created = 0, conflict = 0;
		for (Future<Integer> f : results) {
			int s = f.get();
			if (s == 201) created++;
			else if (s == 409) conflict++;
		}
		pool.shutdown();
		assertThat(created).isEqualTo(1);
		assertThat(conflict).isEqualTo(n - 1);
		assertThat(userRepository.count()).isEqualTo(1);
	}

	// ---------- 조건 5: 로그인·재발급·me 응답 ----------

	@Test
	void 조건5_로그인_재발급_me_응답에_nickname이_들어_있다() throws Exception {
		닉네임가입("user01", "홍길동").andExpect(status().isCreated());

		MockHttpServletResponse login = 로그인("user01");
		assertThat(login.getStatus()).isEqualTo(200);
		assertThat(om.readTree(login.getContentAsString()).get("nickname").asString()).isEqualTo("홍길동");

		MockHttpServletResponse refresh = mockMvc.perform(post("/auth/refresh").with(csrf())
				.cookie(new Cookie("refresh_token", login.getCookie("refresh_token").getValue())))
				.andReturn().getResponse();
		assertThat(refresh.getStatus()).isEqualTo(200);
		JsonNode rb = om.readTree(refresh.getContentAsString());
		assertThat(rb.get("nickname").asString()).isEqualTo("홍길동");
		assertThat(rb.has("password")).isFalse();

		mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", login.getCookie("access_token").getValue())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.nickname").value("홍길동"))
				.andExpect(jsonPath("$.username").value("user01"))
				.andExpect(jsonPath("$.password").doesNotExist());
	}

	// ---------- 조건 6: 토큰은 유효한데 회원 행이 없는 경우 ----------

	@Test
	void 조건6_유효한_토큰이어도_회원_행이_없으면_me는_401() throws Exception {
		닉네임가입("user01", "홍길동").andExpect(status().isCreated());
		Long id = userRepository.findByUsername("user01").orElseThrow().getId();
		// 서명이 맞는 정상 토큰을 직접 만든다 (sub = 회원 id)
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder().subject(String.valueOf(id)).claim("username", "user01")
				.issuedAt(now).expiresAt(now.plusSeconds(900)).build();
		String token = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
				.getTokenValue();
		// 행이 있을 때는 200
		mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", token))).andExpect(status().isOk());
		// 행을 지우면 같은 토큰이 401
		userRepository.deleteAll();
		mockMvc.perform(get("/auth/me").cookie(new Cookie("access_token", token))).andExpect(status().isUnauthorized());
	}

	// ---------- 조건 7: 글 응답의 author ----------

	@Test
	void 조건7_글_단건과_목록의_author는_id_nickname뿐이고_username이_없으며_작성자_없는_글은_null() throws Exception {
		User u = userRepository.save(new User("user01", "홍길동", "{noop}x"));
		Post withAuthor = postRepository.save(new Post("제목", "내용", u, null, PostCategory.CHAT));
		Post noAuthor = postRepository.save(new Post("옛글", "내용", null, null, PostCategory.CHAT));

		JsonNode one = om.readTree(mockMvc.perform(get("/posts/" + withAuthor.getId())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		JsonNode a = one.get("author");
		assertThat(a.get("id").asLong()).isEqualTo(u.getId());
		assertThat(a.get("nickname").asString()).isEqualTo("홍길동");
		assertThat(a.has("username")).isFalse();
		assertThat(a.size()).isEqualTo(2);

		JsonNode oneNo = om.readTree(mockMvc.perform(get("/posts/" + noAuthor.getId())).andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString());
		assertThat(oneNo.get("author").isNull()).isTrue();

		String listBody = mockMvc.perform(get("/posts")).andExpect(status().isOk()).andReturn().getResponse()
				.getContentAsString();
		assertThat(listBody).doesNotContain("user01"); // 로그인 아이디가 어디에도 새지 않는다
		for (JsonNode item : om.readTree(listBody)) {
			if (item.get("id").asLong() == withAuthor.getId()) {
				assertThat(item.get("author").has("username")).isFalse();
				assertThat(item.get("author").get("nickname").asString()).isEqualTo("홍길동");
			} else {
				assertThat(item.get("author").isNull()).isTrue();
			}
		}
	}

	// ---------- 조건 8: DB 제약 ----------

	@Test
	void 조건8_nickname이_null이면_DB가_저장을_거부한다() {
		assertThatThrownBy(() -> userRepository.saveAndFlush(new User("user01", null, "{noop}x")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void 조건8_중복_nickname은_DB가_저장을_거부한다() {
		userRepository.saveAndFlush(new User("user01", "홍길동", "{noop}x"));
		assertThatThrownBy(() -> userRepository.saveAndFlush(new User("user02", "홍길동", "{noop}x")))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(userRepository.count()).isEqualTo(1);
	}
}
