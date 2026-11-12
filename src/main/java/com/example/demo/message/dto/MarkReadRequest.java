package com.example.demo.message.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.jdk.NumberDeserializers;

// 읽음 처리 요청 본문. 화면이 대화방을 받은 뒤 "내가 본 마지막 쪽지 번호"를 보낸다(그 번호까지만 읽음 처리).
// 없음·null·0 이하는 400. 숫자가 아닌 값·소수(1.5)는 JSON 변환 단계에서 400.
// 소수 막기는 이 칸 하나에만 건다(@JsonDeserialize). 전역 Jackson 설정은 바꾸지 않는다.
public record MarkReadRequest(
        @NotNull @Positive @JsonDeserialize(using = MarkReadRequest.NoDecimalLongDeserializer.class) Long lastMessageId) {

    // 기본 Long 변환은 1.5를 1로 잘라 받는다. 소수 숫자(1.5, 1.0, 1e2)가 오면 거절하고,
    // 나머지(정수, 범위 초과, 문자열 등)는 기본 변환기에게 그대로 맡긴다.
    public static class NoDecimalLongDeserializer extends ValueDeserializer<Long> {

        // 기본 Long 변환기
        private final ValueDeserializer<Long> delegate = new NumberDeserializers.LongDeserializer(Long.class, null);

        @Override
        public Long deserialize(JsonParser p, DeserializationContext ctxt) {
            if (p.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
                return ctxt.reportInputMismatch(Long.class, "lastMessageId는 소수가 아닌 정수여야 합니다.");
            }
            return delegate.deserialize(p, ctxt);
        }
    }
}
