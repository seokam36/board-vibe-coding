package com.example.demo.auth.controller;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.CsrfResponse;

// CSRF 쿠키(XSRF-TOKEN)를 발급한다. 토큰은 쓰는 순간에만 만들어지는 지연 방식이라,
// 여기서 값을 읽어야(getToken) 쿠키가 응답에 실린다. SPA는 앱 시작 시 한 번 호출한다.
@RestController
public class CsrfController {

    @GetMapping("/csrf")
    public CsrfResponse csrf(CsrfToken token) {
        token.getToken(); // 읽는 것만으로 XSRF-TOKEN 쿠키가 설정된다
        return new CsrfResponse(token.getHeaderName());
    }
}
