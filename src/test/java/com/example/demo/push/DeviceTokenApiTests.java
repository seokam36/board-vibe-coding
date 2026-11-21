package com.example.demo.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import jakarta.servlet.http.Cookie;

// S24-a 완료 조건 검사: 기기 토큰(푸시 알림용 휴대폰 주소) 등록·삭제 (/users/me/device-tokens),
// 최대 10개, 같은 토큰 주인 바꾸기, 동시 PUT, 비밀번호 변경·탈퇴 연동, 로그에 토큰 값 없음.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class DeviceTokenApiTests {

	private static final String URL = "/users/me/device-tokens";
	private static final String PW = "password123";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired JdbcTemplate jdbc;
	@Autowired TokenService tokenService;
	@Autowired JwtEncoder jwtEncoder;

	User 회원A;
	User 회원B;
	Cookie 쿠키A;
	Cookie 쿠키B;

	@BeforeEach
	void 준비() {
		정리();
		회원A = TestLogin.사용자(userRepository, "tokenusera");
		회원B = TestLogin.사용자(userRepository, "tokenuserb");
		쿠키A = TestLogin.쿠키(tokenService, 회원A);
		쿠키B = TestLogin.쿠키(tokenService, 회원B);
	}

	@AfterEach
	void 정리() {
		jdbc.update("delete from device_tokens");
		userRepository.deleteAll();
	}

	// ---------- 도우미 ----------

	private ResultActions 등록(Cookie 쿠키, String bodyJson) throws Exception {
		return mockMvc.perform(put(URL).with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON).content(bodyJson));
	}

	private ResultActions 등록값(Cookie 쿠키, String token) throws Exception {
		return 등록(쿠키, "{\"token\":\"" + token + "\"}");
	}

	private ResultActions 삭제값(Cookie 쿠키, String token) throws Exception {
		return mockMvc.perform(delete(URL).with(csrf()).cookie(쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\"}"));
	}

	private int 전체행수() {
		return jdbc.queryForObject("select count(*) from device_tokens", Integer.class);
	}

	private int 행수(String token) {
		return jdbc.queryForObject("select count(*) from device_tokens where token = ?", Integer.class, token);
	}

	private int 회원행수(User user) {
		return jdbc.queryForObject("select count(*) from device_tokens where user_id = ?", Integer.class, user.getId());
	}

	private Long 주인(String token) {
		return jdbc.queryForObject("select user_id from device_tokens where token = ?", Long.class, token);
	}

	// ---------- 등록 ----------

	@Test
	void 로그인_회원이_등록하면_204이고_그_회원의_행이_1개_생긴다() throws Exception {
		등록값(쿠키A, "abc").andExpect(status().isNoContent());

		assertThat(전체행수()).isEqualTo(1);
		assertThat(주인("abc")).isEqualTo(회원A.getId());
	}

	@Test
	void 같은_토큰을_다시_보내도_204이고_행은_1개다() throws Exception {
		등록값(쿠키A, "abc").andExpect(status().isNoContent());
		등록값(쿠키A, "abc").andExpect(status().isNoContent());

		assertThat(전체행수()).isEqualTo(1);
	}

	@Test
	void 남이_등록한_토큰을_다른_회원이_보내면_주인이_바뀌고_행은_1개다() throws Exception {
		등록값(쿠키A, "abc").andExpect(status().isNoContent());
		등록값(쿠키B, "abc").andExpect(status().isNoContent());

		assertThat(행수("abc")).isEqualTo(1);
		assertThat(주인("abc")).isEqualTo(회원B.getId());
	}

	@Test
	void 다시_등록하면_updated_at이_갱신된다() throws Exception {
		등록값(쿠키A, "abc").andExpect(status().isNoContent());
		jdbc.update("update device_tokens set updated_at = now() - interval '1 day'");

		등록값(쿠키A, "abc").andExpect(status().isNoContent());

		Boolean 갱신됨 = jdbc.queryForObject("select updated_at > now() - interval '1 hour' from device_tokens", Boolean.class);
		assertThat(갱신됨).isTrue();
	}

	@Test
	void token이_없거나_비었거나_공백뿐이거나_4097자면_400이고_행은_늘지_않는다() throws Exception {
		등록(쿠키A, "{}").andExpect(status().isBadRequest());
		등록(쿠키A, "{\"token\":null}").andExpect(status().isBadRequest());
		등록값(쿠키A, "").andExpect(status().isBadRequest());
		등록값(쿠키A, "   ").andExpect(status().isBadRequest());
		등록값(쿠키A, "a".repeat(4097)).andExpect(status().isBadRequest());

		assertThat(전체행수()).isZero();
	}

	@Test
	void 본문이_없거나_JSON이_깨져도_400이다() throws Exception {
		mockMvc.perform(put(URL).with(csrf()).cookie(쿠키A)).andExpect(status().isBadRequest());
		등록(쿠키A, "{token").andExpect(status().isBadRequest());
	}

	@Test
	void 정확히_4096자는_204로_저장된다() throws Exception {
		String 긴토큰 = "a".repeat(4096);

		등록값(쿠키A, 긴토큰).andExpect(status().isNoContent());

		assertThat(행수(긴토큰)).isEqualTo(1);
	}

	@Test
	void 한_회원이_다른_토큰_11개를_등록하면_10개만_남고_처음_것이_지워진다() throws Exception {
		for (int i = 1; i <= 11; i++) {
			등록값(쿠키A, "token-" + i).andExpect(status().isNoContent());
		}

		assertThat(회원행수(회원A)).isEqualTo(10);
		assertThat(행수("token-1")).isZero();
		assertThat(행수("token-2")).isEqualTo(1);
		assertThat(행수("token-11")).isEqualTo(1);
	}

	@Test
	void 가득_찬_회원이_이미_가진_토큰을_다시_보내도_아무것도_지워지지_않는다() throws Exception {
		for (int i = 1; i <= 10; i++) {
			등록값(쿠키A, "token-" + i).andExpect(status().isNoContent());
		}

		등록값(쿠키A, "token-1").andExpect(status().isNoContent()); // 새 토큰이 아니라 갱신

		assertThat(회원행수(회원A)).isEqualTo(10);
		assertThat(행수("token-1")).isEqualTo(1);
	}

	@Test
	void 재등록한_토큰은_오래된_순서에서_뒤로_가서_11번째_등록때_지워지지_않는다() throws Exception {
		for (int i = 1; i <= 10; i++) {
			등록값(쿠키A, "token-" + i).andExpect(status().isNoContent());
			// 같은 시각이면 순서를 못 가리므로 등록 순서대로 시각을 벌려 둔다
			jdbc.update("update device_tokens set updated_at = now() - interval '1 hour' * ? where token = ?", 100 - i, "token-" + i);
		}

		등록값(쿠키A, "token-1").andExpect(status().isNoContent()); // 가장 오래된 것을 갱신 -> 가장 최신
		등록값(쿠키A, "token-new").andExpect(status().isNoContent());

		assertThat(회원행수(회원A)).isEqualTo(10);
		assertThat(행수("token-1")).isEqualTo(1); // 갱신했으니 살아남음
		assertThat(행수("token-2")).isZero(); // 이제 가장 오래된 것이 지워짐
		assertThat(행수("token-new")).isEqualTo(1);
	}

	@Test
	void V16_제약_이름이_실제_DB에_있다() {
		List<String> 제약 = jdbc.queryForList(
				"select conname from pg_constraint where conrelid = 'device_tokens'::regclass", String.class);
		assertThat(제약).contains("device_tokens_pkey", "uk_device_tokens_token", "fk_device_tokens_user");

		List<String> 인덱스 = jdbc.queryForList(
				"select indexname from pg_indexes where tablename = 'device_tokens'", String.class);
		assertThat(인덱스).contains("idx_device_tokens_user");

		// FK가 ON DELETE CASCADE인지(탈퇴 때 DB가 같이 지움)
		String 삭제규칙 = jdbc.queryForObject(
				"select confdeltype::text from pg_constraint where conname = 'fk_device_tokens_user'", String.class);
		assertThat(삭제규칙).isEqualTo("c");
	}

	@Test
	void 다른_회원_토큰은_10개_제한에_영향을_주지_않는다() throws Exception {
		등록값(쿠키B, "b-token").andExpect(status().isNoContent());
		for (int i = 1; i <= 11; i++) {
			등록값(쿠키A, "token-" + i).andExpect(status().isNoContent());
		}

		assertThat(회원행수(회원B)).isEqualTo(1);
	}

	@Test
	void 같은_토큰을_동시에_보내도_모두_204이고_행은_1개다() throws Exception {
		int 개수 = 8;
		ExecutorService pool = Executors.newFixedThreadPool(개수);
		CountDownLatch 출발 = new CountDownLatch(1);
		try {
			List<Future<Integer>> 결과 = new ArrayList<>();
			for (int i = 0; i < 개수; i++) {
				Cookie 쿠키 = (i % 2 == 0) ? 쿠키A : 쿠키B; // 두 회원이 같은 토큰을 동시에
				Callable<Integer> 작업 = () -> {
					출발.await();
					return 등록값(쿠키, "same-token").andReturn().getResponse().getStatus();
				};
				결과.add(pool.submit(작업));
			}
			출발.countDown();
			for (Future<Integer> f : 결과) {
				assertThat(f.get()).isEqualTo(204);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(행수("same-token")).isEqualTo(1);
	}

	@Test
	void 같은_회원이_서로_다른_토큰을_동시에_보내도_모두_204이고_10개를_넘지_않는다() throws Exception {
		for (int i = 1; i <= 9; i++) {
			등록값(쿠키A, "old-" + i).andExpect(status().isNoContent());
		}
		int 개수 = 5;
		ExecutorService pool = Executors.newFixedThreadPool(개수);
		CountDownLatch 출발 = new CountDownLatch(1);
		try {
			List<Future<Integer>> 결과 = new ArrayList<>();
			for (int i = 0; i < 개수; i++) {
				String 토큰 = "new-" + i;
				Callable<Integer> 작업 = () -> {
					출발.await();
					return 등록값(쿠키A, 토큰).andReturn().getResponse().getStatus();
				};
				결과.add(pool.submit(작업));
			}
			출발.countDown();
			for (Future<Integer> f : 결과) {
				assertThat(f.get()).isEqualTo(204);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(회원행수(회원A)).isEqualTo(10);
	}

	@Test
	void NUL이_들어간_토큰은_등록과_삭제_모두_400이고_행은_늘지_않는다() throws Exception {
		등록(쿠키A, "{\"token\":\"abc\\u0000def\"}").andExpect(status().isBadRequest());
		mockMvc.perform(delete(URL).with(csrf()).cookie(쿠키A).contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"abc\\u0000def\"}")).andExpect(status().isBadRequest());

		assertThat(전체행수()).isZero();
	}

	@Test
	void 주인이_바뀌어도_원래_주인의_다른_토큰은_그대로다() throws Exception {
		등록값(쿠키A, "a-1").andExpect(status().isNoContent());
		등록값(쿠키A, "shared").andExpect(status().isNoContent());
		등록값(쿠키A, "a-3").andExpect(status().isNoContent());

		등록값(쿠키B, "shared").andExpect(status().isNoContent());

		assertThat(주인("shared")).isEqualTo(회원B.getId());
		assertThat(행수("a-1")).isEqualTo(1);
		assertThat(행수("a-3")).isEqualTo(1);
		assertThat(회원행수(회원A)).isEqualTo(2);
	}

	@Test
	void 가득_찬_회원이_남의_토큰을_가져오면_자기_것_중_가장_오래된_것만_지워지고_원래_주인_것은_안_지워진다() throws Exception {
		for (int i = 1; i <= 10; i++) {
			등록값(쿠키B, "b-" + i).andExpect(status().isNoContent());
			// 같은 시각이면 순서를 못 가리므로 등록 순서대로 시각을 벌려 둔다
			jdbc.update("update device_tokens set updated_at = now() - interval '1 hour' * ? where token = ?", 100 - i, "b-" + i);
		}
		등록값(쿠키A, "a-1").andExpect(status().isNoContent());
		등록값(쿠키A, "a-2").andExpect(status().isNoContent());

		등록값(쿠키B, "a-1").andExpect(status().isNoContent()); // B가 A의 토큰을 가져옴 -> B는 11개가 됐다가 10개로

		assertThat(주인("a-1")).isEqualTo(회원B.getId());
		assertThat(회원행수(회원B)).isEqualTo(10);
		assertThat(행수("b-1")).isZero(); // B 것 중 가장 오래된 것만 지워짐
		assertThat(행수("b-2")).isEqualTo(1);
		assertThat(행수("a-2")).isEqualTo(1); // 원래 주인 A의 다른 토큰은 그대로
		assertThat(주인("a-2")).isEqualTo(회원A.getId());
	}

	@Test
	void 토큰은_유효하지만_회원이_DB에_없으면_401이다() throws Exception {
		등록값(TestLogin.행없는_회원_토큰(jwtEncoder), "abc").andExpect(status().isUnauthorized());

		assertThat(전체행수()).isZero();
	}

	// ---------- 삭제 ----------

	@Test
	void 내_토큰을_삭제하면_204이고_행이_지워진다() throws Exception {
		등록값(쿠키A, "abc").andExpect(status().isNoContent());

		삭제값(쿠키A, "abc").andExpect(status().isNoContent());

		assertThat(전체행수()).isZero();
	}

	@Test
	void 없는_토큰을_삭제해도_204다() throws Exception {
		삭제값(쿠키A, "nothing").andExpect(status().isNoContent());
	}

	@Test
	void 남의_토큰을_삭제하면_204이지만_남의_행은_그대로다() throws Exception {
		등록값(쿠키B, "bbb").andExpect(status().isNoContent());

		삭제값(쿠키A, "bbb").andExpect(status().isNoContent());

		assertThat(행수("bbb")).isEqualTo(1);
		assertThat(주인("bbb")).isEqualTo(회원B.getId());
	}

	@Test
	void 삭제도_빈_토큰이면_400이다() throws Exception {
		삭제값(쿠키A, "  ").andExpect(status().isBadRequest());
	}

	// ---------- 보안 ----------

	@Test
	void 비로그인_등록과_삭제는_401이다() throws Exception {
		mockMvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(delete(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void CSRF_헤더가_없으면_등록과_삭제는_403이다() throws Exception {
		mockMvc.perform(put(URL).cookie(쿠키A).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(delete(URL).cookie(쿠키A).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
				.andExpect(status().isForbidden());
		assertThat(전체행수()).isZero();
	}

	// ---------- 비밀번호 변경·탈퇴 연동 ----------

	private Cookie 가입_로그인(String username) throws Exception {
		mockMvc.perform(post("/auth/signup").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"nickname\":\"" + username + "\",\"password\":\"" + PW + "\"}"))
				.andExpect(status().isCreated());
		MockHttpServletResponse 응답 = mockMvc.perform(post("/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + username + "\",\"password\":\"" + PW + "\"}")).andReturn().getResponse();
		return new Cookie("access_token", 응답.getCookie("access_token").getValue());
	}

	@Test
	void 비밀번호_변경이_성공하면_그_회원의_토큰이_전부_지워지고_다른_회원_것은_그대로다() throws Exception {
		Cookie 내쿠키 = 가입_로그인("pwtokenuser");
		등록값(내쿠키, "mine-1").andExpect(status().isNoContent());
		등록값(내쿠키, "mine-2").andExpect(status().isNoContent());
		등록값(쿠키B, "other-1").andExpect(status().isNoContent());

		mockMvc.perform(put("/users/me/password").with(csrf()).cookie(내쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"" + PW + "\",\"newPassword\":\"newPassw0rd\"}"))
				.andExpect(status().isNoContent());

		assertThat(행수("mine-1")).isZero();
		assertThat(행수("mine-2")).isZero();
		assertThat(행수("other-1")).isEqualTo(1);
	}

	@Test
	void 비밀번호_변경이_실패하면_토큰은_그대로다() throws Exception {
		Cookie 내쿠키 = 가입_로그인("pwfailuser");
		등록값(내쿠키, "mine-1").andExpect(status().isNoContent());

		mockMvc.perform(put("/users/me/password").with(csrf()).cookie(내쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"currentPassword\":\"wrongPassw0rd\",\"newPassword\":\"newPassw0rd\"}"))
				.andExpect(status().isBadRequest());

		assertThat(행수("mine-1")).isEqualTo(1);
	}

	@Test
	void 탈퇴하면_그_회원의_토큰이_0행이고_다른_회원_것은_그대로다() throws Exception {
		Cookie 내쿠키 = 가입_로그인("withdrawtokenuser");
		등록값(내쿠키, "mine-1").andExpect(status().isNoContent());
		등록값(쿠키B, "other-1").andExpect(status().isNoContent());

		mockMvc.perform(delete("/users/me").with(csrf()).cookie(내쿠키).contentType(MediaType.APPLICATION_JSON)
				.content("{\"password\":\"" + PW + "\"}")).andExpect(status().isNoContent());

		assertThat(행수("mine-1")).isZero();
		assertThat(행수("other-1")).isEqualTo(1);
	}

	// ---------- 로그 ----------

	@Test
	void 등록_삭제_400_401_때_로그에_토큰_값이_남지_않는다(CapturedOutput output) throws Exception {
		String 비밀 = "LOGCHECK-secret-device-token-0123456789";

		등록값(쿠키A, 비밀).andExpect(status().isNoContent());
		삭제값(쿠키A, 비밀).andExpect(status().isNoContent());
		등록(쿠키A, "{\"token\":\"" + 비밀 + "\",\"extra\":}").andExpect(status().isBadRequest()); // 깨진 JSON
		등록값(TestLogin.행없는_회원_토큰(jwtEncoder), 비밀).andExpect(status().isUnauthorized());
		mockMvc.perform(put(URL).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + 비밀 + "\"}"))
				.andExpect(status().isUnauthorized());

		assertThat(output.getAll()).doesNotContain(비밀);
	}
}
