package com.example.demo.push.service;

// 푸시 알림 한 건을 보내는 부품 (S24-b). 빈 등록은 PushConfig가 한다(PUSH_MODE에 따라 Noop 또는 Fcm 중 하나).
// 구현은 예외를 밖으로 던지지 않고 결과(PushResult)로만 알린다.
// 로그·예외에 기기 토큰·액세스 토큰·키 내용을 절대 넣지 않는다.
public interface PushSender {

    // 켜져 있는지. 꺼져 있으면(off) 주기 작업(S24-c)이 보내지도, "보냈다"고 기록하지도 않는다.
    // (꺼진 상태를 결과값(PushResult)에 섞으면 "보냈다/실패했다"와 헷갈려서, 묻는 방식으로 따로 뒀다)
    boolean enabled();

    PushResult send(String token, String title, String body);
}
