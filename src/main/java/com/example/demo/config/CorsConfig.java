package com.example.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 브라우저(프론트엔드)가 다른 주소에서 이 API를 부를 수 있도록 CORS를 전역으로 허용한다.
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    // 허용할 프론트엔드 주소 (쉼표로 여러 개 가능, 환경변수로 바꿀 수 있음)
    @Value("${app.cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 기본 허용 메서드(GET, HEAD, POST)에는 수정(PUT)·삭제(DELETE)가 없어서 직접 지정한다.
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "HEAD", "POST", "PUT", "DELETE");
    }
}
