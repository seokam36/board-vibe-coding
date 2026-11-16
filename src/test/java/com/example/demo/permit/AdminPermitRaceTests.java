package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

import jakarta.servlet.http.Cookie;

// S20-b 완료 조건 6: 동시성. 신청자 회원 행을 외부에서 잠가 두고 요청들을 모두 잠금 대기시킨 뒤 한꺼번에 깨운다(결정적 재현).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class AdminPermitRaceTests extends AdminPermitTestBase {

	private Cookie 두번째관리자쿠키() {
		User admin2 = com.example.demo.TestLogin.관리자(userRepository, jdbc, "padmin2");
		return com.example.demo.TestLogin.관리자_쿠키(tokenService, admin2);
	}

	private Callable<Integer> 승인호출(Cookie c, long id, String number) {
		return () -> 승인(c, id, 승인본문(연세id, number), true).getStatus();
	}

	@Test
	void 같은_신청을_두_관리자가_동시에_승인하면_하나만_200이고_하나는_409다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		Cookie c2 = 두번째관리자쿠키();

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()), List.of(승인호출(관리자쿠키, id, "N1"), 승인호출(c2, id, "N2")));

		assertThat(res).containsExactlyInAnyOrder(200, 409);
		assertThat(신청행(id).get("status")).isEqualTo("APPROVED");
		assertThat(인증학교(u)).isNotNull();
		assertThat(파일수()).isZero();
	}

	@Test
	void 승인과_반려가_동시에_오면_하나만_200이고_결과가_일관된다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		Cookie c2 = 두번째관리자쿠키();

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()),
				List.of(승인호출(관리자쿠키, id, "N1"), () -> 반려(c2, id, "{\"reason\":\"BLURRY\"}", true).getStatus()));

		assertThat(res).containsExactlyInAnyOrder(200, 409);
		String 상태 = (String) 신청행(id).get("status");
		if (상태.equals("APPROVED")) {
			assertThat(인증학교(u)).isNotNull();
		} else {
			assertThat(상태).isEqualTo("REJECTED");
			assertThat(인증학교(u)).isNull();
		}
		assertThat(파일수()).isZero();
	}

	@Test
	void 서로_다른_두_신청에_같은_번호를_동시에_승인하면_하나만_200이고_하나는_409다() throws Exception {
		User a = 회원("pa");
		User b = 회원("pb");
		long ida = 신청(a);
		long idb = 신청(b);
		Cookie c2 = 두번째관리자쿠키();

		List<Integer> res = 잠가놓고_동시에(List.of(a.getId(), b.getId()),
				List.of(승인호출(관리자쿠키, ida, "SAME-1"), 승인호출(c2, idb, "same 1")));

		assertThat(res).containsExactlyInAnyOrder(200, 409);
		assertThat(jdbc.queryForObject("select count(*) from permit_requests where status = 'APPROVED'", Long.class))
				.isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from users where verified_school_id is not null", Long.class))
				.isEqualTo(1);
		// 진 쪽은 신청·사진이 그대로 대기 중이다
		assertThat(jdbc.queryForObject("select count(*) from permit_requests where status = 'PENDING' and photo_key is not null",
				Long.class)).isEqualTo(1);
		assertThat(파일수()).isEqualTo(1);
	}

	@Test
	void 승인과_회원_철회가_동시에_와도_교착_없이_끝나고_결과가_일관된다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		Cookie 회원쿠키 = 쿠키(u);

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()), List.of(승인호출(관리자쿠키, id, "N1"),
				() -> mockMvc.perform(delete(ME_URL).cookie(회원쿠키).with(csrf())).andReturn().getResponse().getStatus()));

		int 승인결과 = res.get(0);
		int 철회결과 = res.get(1);
		assertThat(승인결과).as("승인은 200 또는 404(철회가 먼저)").isIn(200, 404);
		assertThat(철회결과).as("철회는 204 또는 404").isIn(204, 404);
		assertThat(res).doesNotContain(500);
		// 어느 순서여도 신청 행은 없고 회원은 인증이 풀려 있고 사진은 없다
		assertThat(행수()).isZero();
		assertThat(인증학교(u)).isNull();
		assertThat(파일수()).isZero();
		assertThat(json(내상태(u)).get("status").asString()).isEqualTo("NONE");
	}

	@Test
	void 승인과_회원_탈퇴가_동시에_와도_교착_없이_끝나고_탈퇴가_먼저면_404다() throws Exception {
		User u = 회원("pa");
		long id = 신청(u);
		Cookie 회원쿠키 = 쿠키(u);

		List<Integer> res = 잠가놓고_동시에(List.of(u.getId()), List.of(승인호출(관리자쿠키, id, "N1"),
				() -> mockMvc.perform(delete("/users/me").with(csrf()).cookie(회원쿠키).contentType(MediaType.APPLICATION_JSON)
						.content("{\"password\":\"unused-password\"}")).andReturn().getResponse().getStatus()));

		assertThat(res).doesNotContain(500);
		assertThat(res.get(0)).as("승인은 200 또는 404").isIn(200, 404);
		assertThat(res.get(1)).as("탈퇴").isEqualTo(204);
		// 탈퇴가 끝났으니 회원도 신청도 없다
		assertThat(userRepository.findById(u.getId())).isEmpty();
		assertThat(행수()).isZero();
		assertThat(파일수()).isZero();
	}
}
