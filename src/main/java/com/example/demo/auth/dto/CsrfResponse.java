package com.example.demo.auth.dto;

// CSRF 안내 응답. 프론트는 XSRF-TOKEN 쿠키 값을 headerName 헤더에 실어 POST/PUT/DELETE를 보낸다.
// (본문에 토큰 값을 넣지 않는다: SPA 방식은 본문 토큰을 BREACH 방어용으로 한 번 섞어서 내보내는데,
//  그 값은 헤더로 보내면 검증에 실패한다. 헤더에는 쿠키의 원본 값을 써야 한다.)
public record CsrfResponse(String headerName) {
}
