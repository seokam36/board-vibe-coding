package com.example.demo.push.service;

// 알림 한 건을 보낸 결과 (S24-b)
public enum PushResult {
    // 구글이 받아 줬다(200)
    SENT,
    // 이 기기 토큰은 더 이상 쓸 수 없다(앱 삭제 등, FCM 404 UNREGISTERED) -> 호출하는 쪽이 토큰을 지운다
    TOKEN_INVALID,
    // 구글이 4xx로 "안 된다"고 답했다(400 엉터리 토큰·401/403 권한·UNREGISTERED 없는 404 등). 구글은 살아 있고, 토큰·요청 쪽 문제다.
    // 토큰은 그대로 둔다(설정 실수일 수도 있어서). 호출하는 쪽은 "구글 장애"로 세지 않는다 (S24-c)
    REJECTED,
    // 구글이 답을 못 했다: 연결 실패·시간 초과·5xx·429(한도)·출입증(액세스 토큰) 못 받음·3xx·빈 값. 토큰은 그대로 둔다
    FAILED
}
