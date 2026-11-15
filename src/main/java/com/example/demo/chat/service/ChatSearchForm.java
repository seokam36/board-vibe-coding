package com.example.demo.chat.service;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 내부용: AI가 "양식 호출"에서 채운 검색 조건. JSON 글자를 Jackson이 이 모양으로 읽어 준다(📦 Jackson이 한 일).
// 모르는 칸이 와도 무시한다(AI가 tradeType·needSearch 같은 뺀 칸을 보내도 무시: 나눔 조건은 S17-e4, needSearch는 S17-e5 결정). 칸이 비면 null이다.
// (needSearch를 뺀 이유: AI가 물건이 있는 질문에도 false를 줘 검색을 안 했다. 검색할지는 keyword가 비었는지로 정한다.)
// - keyword: 찾을 물건 단어(공백으로 구분) / maxPrice: 이 가격(원) 이하
// maxPrice가 "30000"처럼 문자열이어도 Jackson이 숫자로 바꿔 읽어 준다. 못 읽으면(예: "삼만") 읽기 실패 = JSON 깨짐으로 취급한다.
// maxPrice는 일부러 BigDecimal(아주 큰 수·소수도 담는 숫자)로 받는다: Integer면 99999999999999 같은 큰 수에서 읽기가 실패해 양식 전체가 버려졌다.
// 값의 범위 검사(1 ~ 10,000,000)는 ChatService가 한다.
@JsonIgnoreProperties(ignoreUnknown = true)
record ChatSearchForm(String keyword, BigDecimal maxPrice) {
}
