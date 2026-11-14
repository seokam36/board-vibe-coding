package com.example.demo.chat.dto;

import com.example.demo.market.domain.ItemStatus;
import com.example.demo.market.domain.TradeType;

// 내부용(API 응답이 아님): AI에게 줄 중고 글 한 건의 "복사본". 엔티티를 트랜잭션 밖으로 내보내지 않으려고(지연 로딩 문제) 검색 서비스가 이 값으로 바꿔 준다.
// 일부러 판매자(닉네임·회원 id)를 담지 않는다: AI와 응답에 회원 정보가 나가지 않게 하려고.
// title = 원본 제목(sources용, 28번 응답과 같다) / promptTitle = 줄바꿈·연속 공백을 한 칸으로 줄인 제목(AI 프롬프트용)
// snippet = 본문 앞부분(공백 정리 후 최대 300자)
public record ChatMarketItem(Long id, String title, String promptTitle, TradeType tradeType, int price,
        ItemStatus status, String schoolName, String snippet) {
}
