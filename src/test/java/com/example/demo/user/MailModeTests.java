package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestcontainersConfiguration;
import com.example.demo.user.service.LogVerificationMailSender;
import com.example.demo.user.service.SmtpVerificationMailSender;
import com.example.demo.user.service.VerificationMailSender;

// S10-e 완료 조건 1: 기본(log) 모드에서는 로그 발송기만 있고 SMTP 발송기는 없으며, 앱 상태(health)가 UP이다.
// 가짜 발송기를 덮어쓰지 않으므로 실제 설정 그대로의 컨텍스트를 검사한다.
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MailModeTests {

	@Autowired ApplicationContext context;
	@Autowired VerificationMailSender sender;
	@Autowired MockMvc mockMvc;

	@Test
	void 기본_모드에서는_발송기가_로그_발송기이고_SMTP_발송기_빈은_없다() {
		assertThat(sender).isInstanceOf(LogVerificationMailSender.class);
		assertThat(context.getBeansOfType(SmtpVerificationMailSender.class)).isEmpty();
	}

	// 빈 host여도 Boot가 JavaMailSender 빈은 만들지만(안 씀), 로그 발송기가 쓰이므로 실제 발송은 없다는 사실을 고정
	@Test
	void 기본_모드에서도_JavaMailSender_빈은_있지만_host가_비어_있다() {
		assertThat(context.getBeansOfType(JavaMailSender.class)).hasSize(1);
		assertThat(((org.springframework.mail.javamail.JavaMailSenderImpl) context.getBean(JavaMailSender.class)).getHost()).isNullOrEmpty();
	}

	@Test
	void 기본_모드에서_actuator_health는_UP이다() throws Exception {
		var res = mockMvc.perform(get("/actuator/health")).andReturn().getResponse();
		assertThat(res.getStatus()).isEqualTo(200);
		assertThat(res.getContentAsString()).contains("\"status\":\"UP\"");
	}
}
