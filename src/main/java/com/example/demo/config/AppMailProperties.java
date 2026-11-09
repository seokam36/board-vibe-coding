package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// application.properties의 app.mail.* 설정. mode: log(가짜 발송) 또는 smtp(실제 발송) / from: 보내는 사람 주소
@ConfigurationProperties("app.mail")
public record AppMailProperties(@DefaultValue("log") String mode, String from) {
}
