package com.example.demo.market.domain;

// 구글 SafeSearch가 주는 "가능성" 등급. 순서가 곧 세기다(위가 약함, 아래가 셈).
// 왜: 등급 글자("LIKELY")를 그냥 문자열로 비교하면 틀리기 쉬워서, 순서가 있는 enum으로 만들어 크기를 비교한다.
public enum SafeSearchLikelihood {
    UNKNOWN, VERY_UNLIKELY, UNLIKELY, POSSIBLE, LIKELY, VERY_LIKELY;

    // 구글 응답 글자를 등급으로 바꾼다. 모르는 글자는 UNKNOWN(= 아무 기준도 안 넘음)으로 본다.
    public static SafeSearchLikelihood fromApi(String text) {
        if (text == null) {
            return UNKNOWN;
        }
        try {
            return valueOf(text);
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
