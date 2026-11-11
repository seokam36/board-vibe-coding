package com.example.demo.market.dto;

import com.example.demo.market.domain.TradeType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// 물건 글 올리기 요청 본문. 모양 검사는 @Valid가 400으로 걸러 낸다.
// 학교·판매자·상태는 받지 않는다(schoolId 같은 모르는 필드는 무시): 서버가 정한다.
// 가격은 거래 방식에 따라 규칙이 달라서(판매 1~10,000,000, 나눔 0) 서비스에서 본다. 그래서 Integer(없을 수 있음)로 받는다.
// PostgreSQL은 NUL(\u0000) 문자를 저장하지 못해 500이 나므로 제목·본문에서 미리 막는다.
public record MarketItemRequest(
        @NotBlank @Size(max = 100) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String title,
        @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\x00]*", message = "NUL 문자는 사용할 수 없습니다") String content,
        @NotNull TradeType tradeType,
        Integer price) {
}
