package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

// S20-d2 완료 조건 2의 "가짜 없이" 쪽: 운영 기본 판독기(NoopPermitTextReader)만 있을 때 모든 신청이 UNREADABLE로 대기한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class PermitAutoReviewNoopReaderTests extends AdminPermitTestBase {

	@Test
	void 운영_기본_판독기는_항상_못_읽어서_UNREADABLE_대기() throws Exception {
		User u = 회원("noop1");

		MockHttpServletResponse r = mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
				.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse();

		assertThat(r.getStatus()).isEqualTo(201);
		assertThat(json(r).get("status").asText()).isEqualTo("PENDING");
		Map<String, Object> row = jdbc.queryForMap("select * from permit_requests where user_id = ?", u.getId());
		assertThat(row.get("status")).isEqualTo("PENDING");
		assertThat(row.get("machine_result")).isEqualTo("UNREADABLE");
		assertThat(row.get("auto_decided")).isEqualTo(false);
		assertThat(row.get("photo_key")).isNotNull();
		assertThat(파일수()).isEqualTo(1);
		assertThat(redis.opsForValue().get("permit-upload-day:" + u.getId() + ":" + LocalDate.now(ZoneId.of("Asia/Seoul"))))
				.isEqualTo("1");
	}
}
