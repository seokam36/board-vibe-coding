package com.example.demo.push.service;

// 알림 한 건을 보낸 결과 (S24-b)
public enum PushResult {
    // 구글이 받아 줬다(200)
    SENT,
    // 이 기기 토큰은 더 이상 쓸 수 없다(앱 삭제 등, FCM 404 UNREGISTERED) -> 호출하는 쪽이 토큰을 지운다
    TOKEN_INVALID,
    // 그 밖의 실패(잘못된 요청·인증·한도·서버 오류·시간 초과·연결 실패). 토큰은 그대로 둔다
    FAILED
}
