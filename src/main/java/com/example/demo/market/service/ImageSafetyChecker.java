package com.example.demo.market.service;

import com.example.demo.market.domain.ImageSafetyResult;

// 중고 글 사진 자동 검사기 (S30-a). 사진 바이트를 받아 판정을 돌려준다.
// 약속: 예외를 밖으로 던지지 않는다. 검사 못 한 모든 경우는 ImageSafetyResult.unchecked()로 돌려준다.
// 구현: VisionImageSafetyChecker(구글 SafeSearch), NoopImageSafetyChecker(꺼짐). 테스트는 가짜 구현으로 갈아 끼운다.
public interface ImageSafetyChecker {

    ImageSafetyResult check(byte[] image);
}
