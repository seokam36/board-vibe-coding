package com.example.demo.config;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import com.example.demo.auth.service.AuthCookieFactory;

import jakarta.servlet.http.Cookie;

// 보안 설정을 한 곳에 모은다. 이후 기능(글 권한 등)은 아래 공개 경로 목록/접근 규칙에 한 줄씩 추가한다.
@Configuration
public class SecurityConfig {

    // 로그인 없이 쓸 수 있는 경로를 여기 한 곳에서만 관리한다.
    // 접근 규칙(permitAll)과 "토큰을 읽지 않을 경로"가 같은 목록을 쓰므로 서로 어긋나지 않는다.
    private static RequestMatcher publicEndpoints() {
        PathPatternRequestMatcher.Builder m = PathPatternRequestMatcher.withDefaults();
        return new OrRequestMatcher(
                m.matcher(HttpMethod.GET, "/csrf"),
                m.matcher(HttpMethod.POST, "/auth/signup"),
                m.matcher(HttpMethod.POST, "/auth/login"),
                m.matcher(HttpMethod.POST, "/auth/logout"),
                m.matcher(HttpMethod.POST, "/auth/refresh"),
                m.matcher("/posts/**"), // 글 권한은 S4-d에서 좁힌다
                m.matcher("/actuator/health"),
                m.matcher("/error"));
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder) throws Exception {
        RequestMatcher publicEndpoints = publicEndpoints();
        http
                // 서버가 세션(JSESSIONID)을 만들지 않는다. 로그인 상태는 JWT 쿠키로 구분한다.
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 로그인 폼 리다이렉트(302)와 브라우저 기본 팝업(httpBasic)을 끈다. API 서버라 필요 없다.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                // SPA용 CSRF: XSRF-TOKEN 쿠키(JS가 읽을 수 있음)를 받아 X-XSRF-TOKEN 헤더로 돌려보내게 한다.
                .csrf(csrf -> csrf.spa())
                // 기존 CorsConfig(WebMvcConfigurer)의 허용 주소 설정을 그대로 쓴다.
                .cors(withDefaults())
                // 기본 로그아웃 필터(POST /logout → 302)는 끈다. 로그아웃은 AuthController의 /auth/logout이 처리한다.
                .logout(logout -> logout.disable())
                // 인증이 안 된 요청은 로그인 페이지로 보내지 않고 401만 돌려준다.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                // access_token 쿠키의 JWT를 검증해 로그인 사용자로 인식하는 필터를 직접 끼운다.
                // (oauth2ResourceServer 설정을 쓰면 "토큰이 붙은 요청은 CSRF 검사 면제"가 자동으로 켜져서,
                //  브라우저가 쿠키를 알아서 보내는 우리 구조에서는 CSRF 방어가 무력화된다. 그래서 필터만 직접 등록한다.)
                .addFilterBefore(cookieJwtFilter(jwtDecoder, publicEndpoints), AuthorizationFilter.class)
                // 접근 규칙: 위에서부터 먼저 맞는 규칙이 적용된다.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicEndpoints).permitAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    // 쿠키의 JWT를 검증하는 필터. 토큰이 잘못되면 이유를 노출하지 않고 항상 같은 401만 돌려준다.
    private BearerTokenAuthenticationFilter cookieJwtFilter(JwtDecoder jwtDecoder, RequestMatcher publicEndpoints) {
        BearerTokenAuthenticationFilter filter = new BearerTokenAuthenticationFilter(
                new ProviderManager(new JwtAuthenticationProvider(jwtDecoder)));
        filter.setBearerTokenResolver(cookieTokenResolver(publicEndpoints));
        filter.setAuthenticationFailureHandler(
                new AuthenticationEntryPointFailureHandler(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        return filter;
    }

    // 쿠키에서 토큰을 꺼낸다. 공개 경로에서는 null을 돌려줘 토큰을 아예 검사하지 않는다.
    // (기본 동작은 permitAll 경로여도 만료·변조 토큰이 붙어 오면 401이라, 만료된 쿠키가 남은 브라우저가
    //  로그인·가입·글 목록까지 못 쓰게 되는 문제를 막기 위함)
    private BearerTokenResolver cookieTokenResolver(RequestMatcher publicEndpoints) {
        return request -> {
            if (publicEndpoints.matches(request)) {
                return null;
            }
            Cookie[] cookies = request.getCookies();
            if (cookies == null) {
                return null;
            }
            for (Cookie cookie : cookies) {
                if (AuthCookieFactory.ACCESS_TOKEN_COOKIE.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
                    return cookie.getValue();
                }
            }
            return null;
        };
    }

    // 비밀번호 해시기. {bcrypt} 같은 접두어를 붙여 저장해서, 나중에 알고리즘을 바꿔도 옛 해시를 검증할 수 있다.
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    // 로그인 검증기: UserDetailsService(DB 조회)와 PasswordEncoder(해시 비교)를 묶는다.
    // 없는 아이디도 BadCredentialsException으로 바뀌어(기본 동작) 틀린 비밀번호와 구분되지 않는다.
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
