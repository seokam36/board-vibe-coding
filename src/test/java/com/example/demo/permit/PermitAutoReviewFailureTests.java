package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.permit.service.PermitJudge;
import com.example.demo.permit.service.PermitTextReader;
import com.example.demo.user.domain.User;

// S20-d2 경계: 판독기가 null을 돌려주거나 판정 규칙(PermitJudge)이 예외를 던져도 신청은 201 PENDING으로 끝난다(5xx 금지).
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PermitAutoReviewFailureTests extends AdminPermitTestBase {

	@MockitoBean PermitTextReader 판독기;
	@MockitoSpyBean PermitJudge 판정;

	private MockHttpServletResponse 제출(User u) throws Exception {
		return mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();
	}

	@Test
	void 판독기가_null을_돌려줘도_UNREADABLE로_대기하고_201이다() throws Exception {
		when(판독기.read(any(), any())).thenReturn(null);
		User u = 회원("nullreader");

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		Map<String, Object> row = jdbc.queryForMap("select * from permit_requests where user_id = ?", u.getId());
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("machine_result")).isEqualTo("UNREADABLE");
		assertThat(row.get("photo_key")).isNotNull();
		assertThat(파일수()).isEqualTo(1);
	}

	@Test
	void 판정_규칙이_예외를_던져도_대기로_남고_201이며_횟수는_1이다() throws Exception {
		when(판독기.read(any(), any())).thenReturn(Optional.of("표준입학허가서 아무 글자"));
		doThrow(new IllegalStateException("판정비밀 20130001")).when(판정).judge(anyString());
		User u = 회원("judgeboom");

		MockHttpServletResponse r = 제출(u);

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		Map<String, Object> row = jdbc.queryForMap("select * from permit_requests where user_id = ?", u.getId());
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("auto_decided")).isEqualTo(false);
		assertThat(row.get("photo_key")).isNotNull();
		assertThat(인증학교(u)).isNull();
		assertThat(파일수()).isEqualTo(1);
		assertThat(대기표수()).isZero();
		assertThat(redis.opsForValue().get("permit-upload-day:" + u.getId() + ":" + LocalDate.now(ZoneId.of("Asia/Seoul"))))
				.isEqualTo("1");
	}
}
