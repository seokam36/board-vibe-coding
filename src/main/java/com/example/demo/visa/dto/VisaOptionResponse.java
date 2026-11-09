package com.example.demo.visa.dto;

import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaResult;
import com.example.demo.visa.domain.VisaType;

// 비자 후기의 선택값 하나. {code, name}: code는 API에 보내는 값, name은 화면용 한글 이름 (CategoryResponse와 같은 모양).
// 선택지 목록(VisaReviewOptionsResponse)과 후기 응답(VisaReviewResponse)이 같이 쓴다.
public record VisaOptionResponse(String code, String name) {

    public static VisaOptionResponse from(VisaType type) {
        return new VisaOptionResponse(type.name(), type.getLabel());
    }

    public static VisaOptionResponse from(VisaApplicationType type) {
        return new VisaOptionResponse(type.name(), type.getLabel());
    }

    public static VisaOptionResponse from(VisaResult result) {
        return new VisaOptionResponse(result.name(), result.getLabel());
    }
}
