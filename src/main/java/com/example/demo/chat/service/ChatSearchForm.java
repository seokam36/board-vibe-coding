package com.example.demo.chat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// 내부용: AI가 "양식 호출"에서 채운 검색 조건. JSON 글자를 Jackson이 이 모양으로 읽어 준다(📦 Jackson이 한 일).
// 모르는 칸이 와도 무시한다(AI가 tradeType 같은 뺀 칸을 보내도 무시: S17-e4 결정, 나눔 조건은 쓰지 않는다). 칸이 비면 null이다.
// - needSearch: 중고 글 검색이 필요한 질문인가(없으면 null = 양식이 덜 채워진 것으로 보고 서버가 비상구를 쓴다)
// - keyword: 찾을 물건 단어(공백으로 구분) / maxPrice: 이 가격(원) 이하
// maxPrice가 "30000"처럼 문자열이어도 Jackson이 숫자로 바꿔 읽어 준다. 못 읽으면(예: "삼만") 읽기 실패 = JSON 깨짐으로 취급한다.
@JsonIgnoreProperties(ignoreUnknown = true)
record ChatSearchForm(Boolean needSearch, String keyword, Integer maxPrice) {
}
