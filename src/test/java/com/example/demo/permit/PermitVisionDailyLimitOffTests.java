package com.example.demo.permit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.domain.User;

// S21-d: mode=off(기본)에서는 하루 전체 상한을 세지 않는다. 기존 off 모드 허가서 테스트(PermitAutoReviewApiTests)와
// 같은 설정·@Import라 스프링 컨텍스트를 같이 쓴다(속성을 더하지 않는다). 키 정리는 AdminPermitTestBase가 한다.
@SpringBootTest
@Import({ TestcontainersConfiguration.class, FakePermitTextReader.Config.class })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class PermitVisionDailyLimitOffTests extends AdminPermitTestBase {

	@Autowired FakePermitTextReader 판독기;

	@BeforeEach
	void 준비() {
		판독기.reset();
	}

	// 완료 조건 5: mode=off면 카운터가 없고 판독기는 매번 불린다(기본 상한 30보다 적게 신청하므로 상한과는 무관하게 키 유무만 본다)
	@Test
	void mode가_off면_카운터_키가_안_생기고_판독기가_매번_불린다() throws Exception {
		for (int i = 0; i < 3; i++) {
			User u = 회원("voff" + i);
			int status = mockMvc.perform(multipart(ME_URL).file(new MockMultipartFile("file", "a.jpg", "image/jpeg", 그림()))
					.param("consent", "true").cookie(쿠키(u)).with(csrf())).andReturn().getResponse().getStatus();
			assertThat(status).isEqualTo(201);
		}

		assertThat(판독기.calls).isEqualTo(3);
		assertThat(redis.keys("permit-vision-day:*")).isNullOrEmpty();
	}
}
