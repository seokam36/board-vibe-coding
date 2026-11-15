package com.example.demo.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

// 챗봇 설정 클래스(ChatProperties)를 스프링 설정으로 등록한다.
@Configuration
@EnableConfigurationProperties(ChatProperties.class)
public class ChatConfig {
}
