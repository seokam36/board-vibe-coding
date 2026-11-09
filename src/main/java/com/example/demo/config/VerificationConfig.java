package com.example.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 학교 메일 인증 설정 클래스를 스프링 설정으로 등록한다.
@Configuration
@EnableConfigurationProperties(VerificationProperties.class)
public class VerificationConfig {
}
