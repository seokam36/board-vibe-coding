package com.example.demo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Instant;

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
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.comment.domain.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.post.domain.Post;
import com.example.demo.post.domain.PostCategory;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.domain.UserRole;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// S9-a 완료 조건 2~8 검사: role 응답·토큰 클레임, 관리자 삭제, 강등/유령 토큰, 공감 API의 유령 토큰, CSRF
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AdminRoleTests {

	@Autowired
	MockMvc mockMvc;
	@Autowired
	PostRepository postRepository;
	@Autowired
	CommentRepository commentRepository;
	@Autowired
	UserRepository userRepository;
	@Autowired
	TokenService tokenService;
	@Autowired
	JwtEncoder jwtEncoder;
	@Autowired
	JwtDecoder jwtDecoder;
	@Autowired
	JdbcTemplate jdbc;
	@Autowired
	ObjectMapper om;

	User 글쓴이;
	User 관리자;
	Cookie 글쓴이쿠키;
	Cookie 관리자쿠키;
	Post 글;

	@BeforeEach
	@AfterEach
	void 정리() {
		// 글을 먼저 지우면 댓글·공감은 DB가 CASCADE로 지운다
		postRepository.deleteAll();
		userRepository.deleteAll();
	}

	@BeforeEach
	void 준비() {
		글쓴이 = TestLogin.사용자(userRepository, "writer");
		관리자 = TestLogin.관리자(userRepository, jdbc, "boss");
		글쓴이쿠키 = TestLogin.쿠키(tokenService, 글쓴이);
		관리자쿠키 = TestLogin.관리자_쿠키(tokenService, 관리자);
		글 = postRepository.save(new Post("제목", "내용", 글쓴이, null, PostCategory.CHAT));
	}

	// ---------- 도우미 ----------

	private MockHttpServletResponse 요청(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, Cookie cookie)
			throws Exception {
		if (cookie != null) {
			req.cookie(cookie);
		}
		return mockMvc.perform(req).andReturn().getResponse();
	}

	private MockHttpServletResponse 글삭제(Cookie c, Long id) throws Exception {
		return 요청(delete("/posts/" + id).with(csrf()), c);
	}

	private MockHttpServletResponse 댓글삭제(Cookie c, Long id) throws Exception {
		return 요청(delete("/comments/" + id).with(csrf()), c);
	}

	private Comment 댓글(User author) {
		return commentRepository.save(new Comment(글, author, "댓글내용"));
	}

	private void 회원가입(String u) throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + u + "\",\"nickname\":\"n_" + u + "\",\"password\":\"password123\"}")).andReturn();
	}

	private MockHttpServletResponse 로그인(String u) throws Exception {
		return mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + u + "\",\"password\":\"password123\"}")).andReturn().getResponse();
	}

	private JsonNode 본문(MockHttpServletResponse r) throws Exception {
		return om.readTree(r.getContentAsString());
	}

	private String 토큰_role(MockHttpServletResponse r) {
		return jwtDecoder.decode(r.getCookie("access_token").getValue()).getClaimAsString("role");
	}

	// role 클레임을 마음대로 넣은(또는 아예 뺀) 서명 정상 토큰
	private Cookie 클레임_토큰(User u, Object roleClaim) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder b = JwtClaimsSet.builder().subject(String.valueOf(u.getId())).claim("username", u.getUsername())
				.issuedAt(now).expiresAt(now.plusSeconds(900));
		if (roleClaim != null) {
			b.claim("role", roleClaim);
		}
		String t = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), b.build())).getTokenValue();
		return new Cookie("access_token", t);
	}

	// ---------- 완료 조건 2: role 응답 ----------

	@Test
	void 가입한_회원의_role은_USER다() throws Exception {
		회원가입("newbie");
		assertThat(jdbc.queryForObject("select role from users where username = 'newbie'", String.class)).isEqualTo("USER");
	}

	@Test
	void 가입_응답에는_role_USER가_들어_있다() throws Exception {
		var res = mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"signer\",\"nickname\":\"n_signer\",\"password\":\"password123\"}")).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(본문(res).get("role").asString()).isEqualTo("USER");
	}

	// 가입 본문에 role을 끼워 넣어도 무시된다(관리자는 DB에서만 지정). 201이고 응답·DB 모두 USER
	@Test
	void 가입_본문에_role_ADMIN을_넣어도_USER로_가입된다() throws Exception {
		var res = mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"sneaky\",\"nickname\":\"n_sneaky\",\"password\":\"password123\",\"role\":\"ADMIN\"}")).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(201);
		assertThat(본문(res).get("role").asString()).isEqualTo("USER");
		assertThat(jdbc.queryForObject("select role from users where username = 'sneaky'", String.class)).isEqualTo("USER");
	}

	@Test
	void 일반_회원_로그인_응답과_토큰의_role은_USER다() throws Exception {
		회원가입("normal");
		MockHttpServletResponse res = 로그인("normal");
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(본문(res).get("role").asString()).isEqualTo("USER");
		assertThat(토큰_role(res)).isEqualTo("USER");
	}

	@Test
	void 관리자_로그인_응답과_토큰의_role은_ADMIN이다() throws Exception {
		회원가입("adm1");
		jdbc.update("update users set role = 'ADMIN' where username = 'adm1'");
		MockHttpServletResponse res = 로그인("adm1");
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(본문(res).get("role").asString()).isEqualTo("ADMIN");
		assertThat(토큰_role(res)).isEqualTo("ADMIN");
	}

	@Test
	void 내정보_응답에_role이_있다() throws Exception {
		MockHttpServletResponse 일반 = 요청(get("/auth/me"), 글쓴이쿠키);
		assertThat(일반.getStatus()).isEqualTo(200);
		assertThat(본문(일반).get("role").asString()).isEqualTo("USER");
		MockHttpServletResponse 관리 = 요청(get("/auth/me"), 관리자쿠키);
		assertThat(관리.getStatus()).isEqualTo(200);
		assertThat(본문(관리).get("role").asString()).isEqualTo("ADMIN");
	}

	@Test
	void 재발급_응답에_role이_있다() throws Exception {
		회원가입("refr1");
		MockHttpServletResponse login = 로그인("refr1");
		MockHttpServletResponse res = 요청(post("/auth/refresh").with(csrf()), new Cookie("refresh_token", login.getCookie("refresh_token").getValue()));
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(본문(res).get("role").asString()).isEqualTo("USER");
		assertThat(토큰_role(res)).isEqualTo("USER");
	}

	@Test
	void 재발급은_DB의_현재_role로_새_토큰을_만든다() throws Exception {
		회원가입("refr2");
		MockHttpServletResponse login = 로그인("refr2");
		assertThat(토큰_role(login)).isEqualTo("USER");
		// 로그인 뒤에 DB에서 관리자로 승격
		jdbc.update("update users set role = 'ADMIN' where username = 'refr2'");
		MockHttpServletResponse res = 요청(post("/auth/refresh").with(csrf()), new Cookie("refresh_token", login.getCookie("refresh_token").getValue()));
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(본문(res).get("role").asString()).isEqualTo("ADMIN");
		assertThat(토큰_role(res)).isEqualTo("ADMIN");
	}

	// ---------- 완료 조건 3: role 클레임이 없거나 이상한 토큰 ----------

	@Test
	void role_클레임이_없는_옛_토큰으로_내정보가_200이다() throws Exception {
		MockHttpServletResponse res = 요청(get("/auth/me"), 클레임_토큰(글쓴이, null));
		assertThat(res.getStatus()).isEqualTo(200);
	}

	@Test
	void role_클레임이_없는_옛_토큰은_USER라_남의_글_삭제가_403이다() throws Exception {
		assertThat(글삭제(클레임_토큰(관리자, null), 글.getId()).getStatus()).isEqualTo(403);
		assertThat(postRepository.existsById(글.getId())).isTrue();
	}

	@Test
	void role_클레임이_이상한_값이면_USER로_취급해_남의_글_삭제가_403이다() throws Exception {
		assertThat(글삭제(클레임_토큰(관리자, "SUPERUSER"), 글.getId()).getStatus()).isEqualTo(403);
		assertThat(글삭제(클레임_토큰(관리자, "admin"), 글.getId()).getStatus()).isEqualTo(403);
		assertThat(글삭제(클레임_토큰(관리자, 12345), 글.getId()).getStatus()).isEqualTo(403);
		assertThat(postRepository.existsById(글.getId())).isTrue();
	}

	@Test
	void role_클레임이_이상해도_내정보는_200이다() throws Exception {
		assertThat(요청(get("/auth/me"), 클레임_토큰(글쓴이, "???")).getStatus()).isEqualTo(200);
	}

	// ---------- 완료 조건 4: 관리자 삭제 ----------

	@Test
	void 관리자는_남의_글을_삭제하면_204이고_실제로_지워진다() throws Exception {
		assertThat(글삭제(관리자쿠키, 글.getId()).getStatus()).isEqualTo(204);
		assertThat(postRepository.existsById(글.getId())).isFalse();
	}

	@Test
	void 관리자는_남의_댓글을_삭제하면_204이고_실제로_지워진다() throws Exception {
		Comment c = 댓글(글쓴이);
		assertThat(댓글삭제(관리자쿠키, c.getId()).getStatus()).isEqualTo(204);
		assertThat(commentRepository.existsById(c.getId())).isFalse();
	}

	@Test
	void 관리자는_작성자_없는_옛_글도_삭제하면_204다() throws Exception {
		Post 옛글 = postRepository.save(new Post("옛글", "내용", null, null, PostCategory.CHAT));
		assertThat(글삭제(관리자쿠키, 옛글.getId()).getStatus()).isEqualTo(204);
		assertThat(postRepository.existsById(옛글.getId())).isFalse();
	}

	@Test
	void 관리자는_작성자_없는_옛_댓글도_삭제하면_204다() throws Exception {
		Comment c = 댓글(null);
		assertThat(댓글삭제(관리자쿠키, c.getId()).getStatus()).isEqualTo(204);
		assertThat(commentRepository.existsById(c.getId())).isFalse();
	}

	@Test
	void 관리자도_남의_글_수정은_403이고_내용이_그대로다() throws Exception {
		MockHttpServletResponse res = 요청(put("/posts/" + 글.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"바꿈\",\"content\":\"바꿈\",\"category\":\"QUESTION\"}"), 관리자쿠키);
		assertThat(res.getStatus()).isEqualTo(403);
		assertThat(postRepository.findById(글.getId()).orElseThrow().getTitle()).isEqualTo("제목");
	}

	@Test
	void 관리자도_남의_댓글_수정은_403이다() throws Exception {
		Comment c = 댓글(글쓴이);
		MockHttpServletResponse res = 요청(put("/comments/" + c.getId()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"바꿈\"}"), 관리자쿠키);
		assertThat(res.getStatus()).isEqualTo(403);
		assertThat(commentRepository.findById(c.getId()).orElseThrow().getContent()).isEqualTo("댓글내용");
	}

	// ---------- 완료 조건 5: 일반 회원 ----------

	@Test
	void 일반_회원이_남의_글을_삭제하면_403이고_글이_남는다() throws Exception {
		User other = TestLogin.사용자(userRepository, "other");
		assertThat(글삭제(TestLogin.쿠키(tokenService, other), 글.getId()).getStatus()).isEqualTo(403);
		assertThat(postRepository.existsById(글.getId())).isTrue();
	}

	@Test
	void 일반_회원이_남의_댓글을_삭제하면_403이고_댓글이_남는다() throws Exception {
		User other = TestLogin.사용자(userRepository, "other");
		Comment c = 댓글(글쓴이);
		assertThat(댓글삭제(TestLogin.쿠키(tokenService, other), c.getId()).getStatus()).isEqualTo(403);
		assertThat(commentRepository.existsById(c.getId())).isTrue();
	}

	@Test
	void 일반_회원이_작성자_없는_옛_글을_삭제하면_403이다() throws Exception {
		Post 옛글 = postRepository.save(new Post("옛글", "내용", null, null, PostCategory.CHAT));
		assertThat(글삭제(글쓴이쿠키, 옛글.getId()).getStatus()).isEqualTo(403);
	}

	@Test
	void 본인_글_삭제는_204다() throws Exception {
		assertThat(글삭제(글쓴이쿠키, 글.getId()).getStatus()).isEqualTo(204);
		assertThat(postRepository.existsById(글.getId())).isFalse();
	}

	@Test
	void 본인_댓글_삭제는_204다() throws Exception {
		Comment c = 댓글(글쓴이);
		assertThat(댓글삭제(글쓴이쿠키, c.getId()).getStatus()).isEqualTo(204);
		assertThat(commentRepository.existsById(c.getId())).isFalse();
	}

	// ---------- 완료 조건 6: 강등·유령·없는 글 ----------

	@Test
	void 토큰은_ADMIN인데_DB가_USER면_남의_글_삭제가_403이다() throws Exception {
		// 글쓴이가 아닌 다른 회원이어야 "남의 글"이 된다. DB role은 USER, 토큰만 ADMIN
		User 강등됨 = TestLogin.사용자(userRepository, "demoted");
		Cookie 묵은토큰 = TestLogin.쿠키(tokenService, 강등됨, UserRole.ADMIN);
		assertThat(글삭제(묵은토큰, 글.getId()).getStatus()).isEqualTo(403);
		assertThat(postRepository.existsById(글.getId())).isTrue();
	}

	@Test
	void 토큰은_ADMIN인데_DB가_USER면_남의_댓글_삭제가_403이다() throws Exception {
		User 강등됨 = TestLogin.사용자(userRepository, "demoted");
		Comment c = 댓글(글쓴이);
		assertThat(댓글삭제(TestLogin.쿠키(tokenService, 강등됨, UserRole.ADMIN), c.getId()).getStatus()).isEqualTo(403);
		assertThat(commentRepository.existsById(c.getId())).isTrue();
	}

	@Test
	void 관리자였다가_DB에서_강등되면_옛_토큰으로_삭제가_403이다() throws Exception {
		jdbc.update("update users set role = 'USER' where id = ?", 관리자.getId());
		assertThat(글삭제(관리자쿠키, 글.getId()).getStatus()).isEqualTo(403);
		assertThat(postRepository.existsById(글.getId())).isTrue();
	}

	@Test
	void 토큰은_ADMIN인데_DB에_회원이_없으면_남의_글_삭제가_401이다() throws Exception {
		jdbc.update("update users set role = 'ADMIN' where id = ?", 관리자.getId());
		Cookie 유령관리자 = TestLogin.관리자_쿠키(tokenService, 관리자);
		userRepository.deleteById(관리자.getId());
		assertThat(글삭제(유령관리자, 글.getId()).getStatus()).isEqualTo(401);
		assertThat(postRepository.existsById(글.getId())).isTrue();
	}

	@Test
	void 토큰은_ADMIN인데_DB에_회원이_없으면_남의_댓글_삭제가_401이다() throws Exception {
		Comment c = 댓글(글쓴이);
		Cookie 유령관리자 = TestLogin.관리자_쿠키(tokenService, 관리자);
		userRepository.deleteById(관리자.getId());
		assertThat(댓글삭제(유령관리자, c.getId()).getStatus()).isEqualTo(401);
		assertThat(commentRepository.existsById(c.getId())).isTrue();
	}

	@Test
	void 없는_글을_관리자가_삭제하면_404다() throws Exception {
		assertThat(글삭제(관리자쿠키, 999999L).getStatus()).isEqualTo(404);
	}

	@Test
	void 없는_글을_강등된_관리자_토큰으로_삭제해도_404가_우선이다() throws Exception {
		User 강등됨 = TestLogin.사용자(userRepository, "demoted");
		assertThat(글삭제(TestLogin.쿠키(tokenService, 강등됨, UserRole.ADMIN), 999999L).getStatus()).isEqualTo(404);
	}

	@Test
	void 없는_댓글을_관리자가_삭제하면_404다() throws Exception {
		assertThat(댓글삭제(관리자쿠키, 999999L).getStatus()).isEqualTo(404);
	}

	// ---------- 완료 조건 7: 유령 토큰으로 공감 API ----------

	@Test
	void 유령_토큰으로_공감_취소는_401이다() throws Exception {
		Cookie 유령 = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(요청(delete("/posts/" + 글.getId() + "/likes").with(csrf()), 유령).getStatus()).isEqualTo(401);
	}

	@Test
	void 유령_토큰으로_내_공감_여부_조회는_401이다() throws Exception {
		Cookie 유령 = TestLogin.행없는_회원_토큰(jwtEncoder);
		assertThat(요청(get("/posts/" + 글.getId() + "/likes/me"), 유령).getStatus()).isEqualTo(401);
	}

	// ---------- 완료 조건 8: CSRF ----------

	@Test
	void CSRF_없는_관리자_글_삭제는_403이고_글이_남는다() throws Exception {
		assertThat(요청(delete("/posts/" + 글.getId()), 관리자쿠키).getStatus()).isEqualTo(403);
		assertThat(postRepository.existsById(글.getId())).isTrue();
	}

	@Test
	void CSRF_없는_관리자_댓글_삭제는_403이고_댓글이_남는다() throws Exception {
		Comment c = 댓글(글쓴이);
		assertThat(요청(delete("/comments/" + c.getId()), 관리자쿠키).getStatus()).isEqualTo(403);
		assertThat(commentRepository.existsById(c.getId())).isTrue();
	}
}
