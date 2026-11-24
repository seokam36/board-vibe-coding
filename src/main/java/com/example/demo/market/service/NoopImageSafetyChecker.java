package com.example.demo.market.service;

import com.example.demo.market.domain.ImageSafetyResult;

// 꺼진 검사기(MARKET_SAFESEARCH_MODE=off, 기본): 검사 안 함 = 항상 UNCHECKED. 호출하는 쪽은 통과로 처리한다.
public class NoopImageSafetyChecker implements ImageSafetyChecker {

    @Override
    public ImageSafetyResult check(byte[] image) {
        return ImageSafetyResult.unchecked();
    }
}
