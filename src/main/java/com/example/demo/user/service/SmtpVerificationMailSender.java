package com.example.demo.user.service;

import java.time.Duration;

import org.springframework.core.NestedExceptionUtils;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

// SMTP로 실제 인증 메일을 보내는 발송기 (app.mail.mode=smtp일 때 MailConfig가 빈으로 등록).
// 코드·받는 주소는 로그·예외 메시지 어디에도 남기지 않는다(SMTP 예외 메시지에 주소가 섞일 수 있기 때문).
public class SmtpVerificationMailSender implements VerificationMailSender {

    private static final String SUBJECT = "[학교 인증] 인증 코드 / School verification code";

    private final JavaMailSender mailSender;
    private final String from;
    private final long ttlMinutes;

    public SmtpVerificationMailSender(JavaMailSender mailSender, String from, Duration codeTtl) {
        this.mailSender = mailSender;
        this.from = from;
        this.ttlMinutes = codeTtl.toMinutes();
    }

    @Override
    public void sendCode(String email, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(SUBJECT);
        message.setText("인증 코드: " + code + " (" + ttlMinutes + "분 안에 입력)\n"
                + "Your code: " + code + " (valid for " + ttlMinutes + " minutes)");
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // 원인 예외를 cause로 달면 스택 로그에 주소가 든 메시지가 찍힐 수 있어, 클래스 이름만 적은 새 예외로 감싼다
            // 바깥(MailSendException 등)과 가장 안쪽 원인(연결 거부·타임아웃·주소 거절 구분용) 클래스명을 함께 남긴다
            throw new IllegalStateException("메일 발송 실패 (원인: " + e.getClass().getSimpleName() + " / "
                    + NestedExceptionUtils.getMostSpecificCause(e).getClass().getSimpleName() + ")");
        }
    }
}
