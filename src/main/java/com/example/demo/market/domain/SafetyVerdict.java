package com.example.demo.market.domain;

// 사진 검사 판정. BLOCK = 아주 확실해서 거절, FLAG = 애매해서 올리고 자동 신고, PASS = 통과,
// UNCHECKED = 검사를 못 했거나 안 함(꺼짐·실패·시간 초과·하루 상한) -> 호출하는 쪽이 통과로 처리한다.
public enum SafetyVerdict {
    PASS, FLAG, BLOCK, UNCHECKED
}
