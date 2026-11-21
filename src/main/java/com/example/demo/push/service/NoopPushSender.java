package com.example.demo.push.service;

// 꺼진 발송기(PUSH_MODE=off, 기본): 아무것도 보내지 않는다. enabled()가 false라 호출하는 쪽은 이걸 부르지 않는 게 정상이다.
// 빈 등록은 PushConfig가 한다. 테스트는 @Primary 가짜 발송기로 덮어쓸 수 있다.
public class NoopPushSender implements PushSender {

    @Override
    public boolean enabled() {
        return false;
    }

    // 잘못 불려도 "보냈다"고 거짓말하지 않게 FAILED를 돌려준다
    @Override
    public PushResult send(String token, String title, String body) {
        return PushResult.FAILED;
    }
}
