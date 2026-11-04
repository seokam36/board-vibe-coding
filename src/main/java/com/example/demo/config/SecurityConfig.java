package com.example.demo.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

// 보안 설정을 한 곳에 모은다. 이후 기능(로그인, 글 권한 등)은 아래 접근 규칙에 한 줄씩 추가한다.
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 서버가 세션(JSESSIONID)을 만들지 않는다. 로그인 상태는 이후 단계에서 JWT로 구분한다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 로그인 폼 리다이렉트(302)와 브라우저 기본 팝업(httpBasic)을 끈다. API 서버라 필요 없다.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                // SPA용 CSRF: XSRF-TOKEN 쿠키(JS가 읽을 수 있음)를 받아 X-XSRF-TOKEN 헤더로 돌려보내게 한다.
                .csrf(csrf -> csrf.spa())
                // 기존 CorsConfig(WebMvcConfigurer)의 허용 주소 설정을 그대로 쓴다.
                .cors(withDefaults())
                // 인증이 안 된 요청은 로그인 페이지로 보내지 않고 401만 돌려준다.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // 접근 규칙: 위에서부터 먼저 맞는 규칙이 적용된다. 새 기능은 anyRequest() 위에 한 줄씩 추가한다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/signup").permitAll()
                        .requestMatchers("/posts/**").permitAll() // 글 권한은 S4-d에서 좁힌다
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    // 비밀번호 해시기. {bcrypt} 같은 접두어를 붙여 저장해서, 나중에 알고리즘을 바꿔도 옛 해시를 검증할 수 있다.
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    // 이 빈이 있으면 Boot가 "임시 사용자 + 로그에 찍히는 임시 비밀번호"를 만들지 않는다.
    // 사용자가 0명인 빈 저장소다. 실제 로그인(UserDetailsService)은 S4-b에서 만들 때 이 빈을 대체한다.
    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
