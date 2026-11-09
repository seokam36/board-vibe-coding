package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.TestLogin;
import com.example.demo.TestcontainersConfiguration;
import com.example.demo.auth.service.TokenService;
import com.example.demo.post.repository.PostRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.user.service.SmtpVerificationMailSender;
import com.example.demo.user.service.VerificationMailSender;

import jakarta.servlet.http.Cookie;

// S10-e 완료 조건 2·6: smtp 모드 전체 컨텍스트(가짜 JavaMailSender, 실제 접속 없음)에서 발송 실패 시 API 동작·로그 비노출을 확인한다.
// 속성이 이 클래스에만 적용돼 컨텍스트가 하나 따로 뜨고, 다른 테스트에는 새지 않는다.
@SpringBootTest(properties = { "app.mail.mode=smtp", "spring.mail.host=smtp.invalid", "spring.mail.username=u@test.invalid",
		"spring.mail.password=NeverLogMe-pw", "app.mail.from=from@test.invalid" })
@Import({ TestcontainersConfiguration.class, SmtpModeApiTests.MockMailConfig.class })
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SmtpModeApiTests {

	@TestConfiguration(proxyBeanMethods = false)
	static class MockMailConfig {
		@Bean
		JavaMailSender javaMailSender() {
			return mock(JavaMailSender.class);
		}
	}

	private static final String URL = "/users/me/school-verification/code";
	private static final String EMAIL = "leak@yonsei.ac.kr";

	@Autowired MockMvc mockMvc;
	@Autowired UserRepository userRepository;
	@Autowired PostRepository postRepository;
	@Autowired TokenService tokenService;
	@Autowired StringRedisTemplate redis;
	@Autowired JavaMailSender javaMailSender;
	@Autowired VerificationMailSender sender;

	@BeforeEach
	@AfterEach
	void 정리() {
		reset(javaMailSender);
		postRepository.deleteAll();
		userRepository.deleteAll();
		for (String pattern : new String[] { "refresh:*", "user-refresh:*", "email-verify*" }) {
			Set<String> keys = redis.keys(pattern);
			if (keys != null && !keys.isEmpty()) {
				redis.delete(keys);
			}
		}
	}

	private int 요청(Cookie c) throws Exception {
		return mockMvc.perform(post(URL).with(csrf()).cookie(c).contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + EMAIL + "\"}")).andReturn().getResponse().getStatus();
	}

	@Test
	void smtp_모드_컨텍스트의_발송기는_SmtpVerificationMailSender다() {
		assertThat(sender).isInstanceOf(SmtpVerificationMailSender.class);
	}

	@Test
	void 메일_발송_예외면_500이고_Redis가_되돌려지며_정상화_후_바로_202이고_로그에_주소와_코드가_없다(CapturedOutput output) throws Exception {
		User u = TestLogin.사용자(userRepository, "smtp1");
		Cookie c = TestLogin.쿠키(tokenService, u);
		doThrow(new MailSendException("Invalid Addresses: " + EMAIL)).when(javaMailSender).send(any(SimpleMailMessage.class));

		assertThat(요청(c)).isEqualTo(500);
		assertThat(redis.hasKey("email-verify:" + u.getId())).as("코드 키").isFalse();
		assertThat(redis.hasKey("email-verify-cooldown:" + u.getId())).as("쿨다운 키").isFalse();
		String daily = redis.opsForValue().get("email-verify-daily:" + u.getId());
		assertThat(daily == null || Long.parseLong(daily) == 0L).as("하루 횟수 되돌림(실제 %s)", daily).isTrue();

		// 실패 로그는 남되, 주소는 로그 어디에도 없어야 한다(코드는 목 안에서만 만들어져 아래에서 형태로 검사)
		String log = output.getAll();
		assertThat(log).contains("인증 메일 발송 실패");
		assertThat(log).doesNotContain(EMAIL).doesNotContain("leak@").doesNotContain("NeverLogMe-pw");
		assertThat(log).doesNotContainPattern("(?i)code[=: ]+\\d{6}").doesNotContainPattern("인증 코드: \\d{6}");

		// 메일 서버가 정상으로 돌아오면 쿨다운 없이 곧바로 재요청 성공
		doNothing().when(javaMailSender).send(any(SimpleMailMessage.class));
		assertThat(요청(c)).isEqualTo(202);
	}
}
