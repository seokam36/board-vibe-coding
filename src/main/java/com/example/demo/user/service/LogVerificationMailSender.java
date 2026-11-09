package com.example.demo.user.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// 메일을 실제로 보내지 않고 로그에만 "받는 사람·코드"를 찍는 가짜 발송기 (기본 모드, app.mail.mode=log).
// 코드와 메일을 로그에 남기는 곳은 여기뿐이다. 운영에서 SMTP로 바꾸면(S10-e) 이 로그는 사라진다.
@Component
@ConditionalOnProperty(name = "app.mail.mode", havingValue = "log", matchIfMissing = true)
public class LogVerificationMailSender implements VerificationMailSender {

    private static final Logger log = LoggerFactory.getLogger(LogVerificationMailSender.class);

    @Override
    public void sendCode(String email, String code) {
        log.info("[가짜 메일 발송] to={} code={}", email, code);
    }
}
