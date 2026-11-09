package com.example.demo.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.mail.javamail.JavaMailSender;

import com.example.demo.user.service.SmtpVerificationMailSender;
import com.example.demo.user.service.VerificationMailSender;

// 메일 설정을 시작 때 검사하고, smtp 모드일 때만 실제 발송기 빈을 등록한다.
// 이 클래스의 생성자가 검사한다. 소비 쪽(EmailVerificationService)이 @DependsOn("mailConfig")로 이 빈을 먼저 만들게 해서, 설정이 잘못되면 "어떤 이름이 빠졌는지" 알려 주며 시작이 실패한다(값은 출력 안 함).
@Configuration
@EnableConfigurationProperties(AppMailProperties.class)
public class MailConfig {

    public MailConfig(AppMailProperties props, Environment env) {
        String mode = props.mode();
        if ("log".equals(mode)) {
            return; // log 모드는 SMTP 값이 필요 없다
        }
        if (!"smtp".equals(mode)) {
            throw new IllegalStateException("MAIL_MODE는 log 또는 smtp여야 합니다 (현재 값은 출력하지 않음).");
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(env.getProperty("spring.mail.host"))) missing.add("MAIL_HOST");
        if (isBlank(env.getProperty("spring.mail.username"))) missing.add("MAIL_USERNAME");
        if (isBlank(env.getProperty("spring.mail.password"))) missing.add("MAIL_PASSWORD");
        if (isBlank(props.from())) missing.add("MAIL_FROM");
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "MAIL_MODE=smtp인데 다음 환경변수가 비어 있습니다: " + String.join(", ", missing) + " (.env.example 참고).");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Bean
    @ConditionalOnProperty(name = "app.mail.mode", havingValue = "smtp")
    VerificationMailSender smtpVerificationMailSender(JavaMailSender mailSender,
            AppMailProperties props, VerificationProperties verification) {
        return new SmtpVerificationMailSender(mailSender, props.from(), verification.codeTtl());
    }
}
