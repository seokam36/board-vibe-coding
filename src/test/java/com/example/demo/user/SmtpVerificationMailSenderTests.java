package com.example.demo.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.example.demo.user.service.SmtpVerificationMailSender;

// S10-e 완료 조건 4·5: SMTP 발송기 단위 테스트(목 JavaMailSender, 실제 접속 없음)
class SmtpVerificationMailSenderTests {

	private static final String TO = "student@yonsei.ac.kr";
	private static final String CODE = "123456";

	private final JavaMailSender mailSender = mock(JavaMailSender.class);
	private final SmtpVerificationMailSender sender = new SmtpVerificationMailSender(mailSender, "noreply@school.test", Duration.ofMinutes(10));

	// 보낸 메일의 받는 사람·보내는 사람·제목·본문(코드, 10분/10 minutes)을 확인
	@Test
	void 보낸_메일의_받는사람_보내는사람_제목_본문이_맞고_본문에_코드와_10분이_있다() {
		sender.sendCode(TO, CODE);

		ArgumentCaptor<SimpleMailMessage> cap = ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(mailSender).send(cap.capture());
		SimpleMailMessage m = cap.getValue();
		assertThat(m.getTo()).containsExactly(TO);
		assertThat(m.getFrom()).isEqualTo("noreply@school.test");
		assertThat(m.getSubject()).isEqualTo("[학교 인증] 인증 코드 / School verification code");
		assertThat(m.getText()).contains(CODE).contains("10분").contains("10 minutes");
	}

	// SMTP 예외 메시지에 주소가 섞여도, 밖으로 던지는 예외 체인에는 주소·코드가 없어야 한다
	@Test
	void 발송_예외가_나면_던지는_예외의_메시지와_cause_체인에_주소와_코드가_없다() {
		doThrow(new MailSendException("Invalid Addresses: " + TO + " code " + CODE)).when(mailSender).send(any(SimpleMailMessage.class));

		assertThatThrownBy(() -> sender.sendCode(TO, CODE)).satisfies(t -> {
			assertThat(t).isInstanceOf(IllegalStateException.class);
			for (Throwable c = t; c != null; c = c.getCause()) {
				assertThat(String.valueOf(c.getMessage())).doesNotContain(TO).doesNotContain(CODE).doesNotContain("yonsei");
			}
			assertThat(t.getCause()).as("원인 예외는 달지 않는다").isNull();
		});
	}

	// 연결 거부·타임아웃 같은 원인을 구분할 수 있게, 가장 안쪽 원인의 클래스명이 메시지에 들어간다(주소는 여전히 없음)
	@Test
	void 발송_예외의_가장_안쪽_원인_클래스명이_메시지에_들어간다() {
		doThrow(new MailSendException("fail " + TO, new java.net.ConnectException("refused " + TO))).when(mailSender)
				.send(any(SimpleMailMessage.class));

		assertThatThrownBy(() -> sender.sendCode(TO, CODE)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("MailSendException").hasMessageContaining("ConnectException").hasMessageNotContaining(TO);
	}
}
