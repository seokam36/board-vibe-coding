package com.example.demo.user.service;

// 인증 코드 메일을 보내는 창구. 구현을 갈아끼울 수 있게 인터페이스로 둔다.
// (log 모드는 로그로 찍는 가짜 구현, smtp 모드는 SmtpVerificationMailSender. 테스트는 이 빈을 대체해 보낸 코드를 꺼내 본다)
// 보내지 못하면 RuntimeException을 던진다 -> 서비스가 저장한 코드를 되돌린다.
public interface VerificationMailSender {

    void sendCode(String email, String code);
}
