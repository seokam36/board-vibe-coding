package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.mail.javamail.JavaMailSender;

import com.example.demo.config.MailConfig;
import com.example.demo.config.VerificationProperties;
import com.example.demo.user.service.SmtpVerificationMailSender;
import com.example.demo.user.service.VerificationMailSender;

// S10-e 완료 조건 2·3: 메일 설정 시작 검사. DB·Redis 없이 MailConfig만 띄우는 가벼운 러너를 쓰고, 실제 SMTP에는 접속하지 않는다.
class MailConfigStartupTests {

	private static final String SECRET = "SuperSecretPw!9";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(MailConfig.class)
			.withBean(VerificationProperties.class, () -> new VerificationProperties(Duration.ofMinutes(10), Duration.ofSeconds(60), 10, 5))
			.withBean(JavaMailSender.class, () -> mock(JavaMailSender.class));

	private static final String[] ALL = { "app.mail.mode=smtp", "spring.mail.host=smtp.example.com",
			"spring.mail.username=user@example.com", "spring.mail.password=" + SECRET, "app.mail.from=from@example.com" };

	private static String[] 하나_비움(String key) {
		String[] copy = ALL.clone();
		for (int i = 0; i < copy.length; i++) {
			if (copy[i].startsWith(key + "=")) {
				copy[i] = key + "=";
			}
		}
		return copy;
	}

	private static String 체인_전체_메시지(Throwable t) {
		StringBuilder sb = new StringBuilder();
		for (; t != null; t = t.getCause()) {
			sb.append(t.getMessage()).append('\n');
		}
		return sb.toString();
	}

	@Test
	void smtp_모드에_네_값이_다_있으면_뜨고_발송기가_SmtpVerificationMailSender다() {
		runner.withPropertyValues(ALL).run(ctx -> {
			assertThat(ctx).hasNotFailed();
			assertThat(ctx.getBean(VerificationMailSender.class)).isInstanceOf(SmtpVerificationMailSender.class);
		});
	}

	@Test
	void smtp_모드에서_host가_비면_시작_실패하고_MAIL_HOST를_알려_준다() {
		검사_실패("spring.mail.host", "MAIL_HOST");
	}

	@Test
	void smtp_모드에서_username이_비면_시작_실패하고_MAIL_USERNAME을_알려_준다() {
		검사_실패("spring.mail.username", "MAIL_USERNAME");
	}

	@Test
	void smtp_모드에서_password가_비면_시작_실패하고_MAIL_PASSWORD를_알려_준다() {
		검사_실패("spring.mail.password", "MAIL_PASSWORD");
	}

	@Test
	void smtp_모드에서_from이_비면_시작_실패하고_MAIL_FROM을_알려_준다() {
		검사_실패("app.mail.from", "MAIL_FROM");
	}

	// 하나만 비웠을 때: 시작 실패 + 빠진 이름만 메시지에 있고 + 설정 값(비밀번호 등)은 메시지에 없다
	private void 검사_실패(String key, String name) {
		runner.withPropertyValues(하나_비움(key)).run(ctx -> {
			assertThat(ctx).hasFailed();
			String msg = 체인_전체_메시지(ctx.getStartupFailure());
			assertThat(msg).contains(name);
			assertThat(msg).doesNotContain(SECRET).doesNotContain("smtp.example.com").doesNotContain("user@example.com");
			String root = NestedExceptionUtils.getMostSpecificCause(ctx.getStartupFailure()).getMessage();
			for (String other : new String[] { "MAIL_HOST", "MAIL_USERNAME", "MAIL_PASSWORD", "MAIL_FROM" }) {
				if (!other.equals(name)) {
					assertThat(root).doesNotContain(other);
				}
			}
		});
	}

	@Test
	void mode가_log도_smtp도_아니면_시작_실패한다() {
		runner.withPropertyValues("app.mail.mode=abc", "spring.mail.password=" + SECRET).run(ctx -> {
			assertThat(ctx).hasFailed();
			assertThat(체인_전체_메시지(ctx.getStartupFailure())).doesNotContain(SECRET);
		});
	}

	@Test
	void log_모드에서는_네_값이_다_비어도_정상_시작하고_SMTP_발송기는_없다() {
		runner.withPropertyValues("app.mail.mode=log", "spring.mail.host=", "spring.mail.username=", "spring.mail.password=",
				"app.mail.from=").run(ctx -> {
					assertThat(ctx).hasNotFailed();
					assertThat(ctx).doesNotHaveBean(SmtpVerificationMailSender.class);
				});
	}
}
