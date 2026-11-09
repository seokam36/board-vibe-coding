package com.example.demo.visa.dto;

import java.util.Arrays;
import java.util.List;

import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaResult;
import com.example.demo.visa.domain.VisaType;

// 비자 후기 작성 화면의 선택지 모음. 각 항목은 {code, name} (CategoryResponse·ReportReasonResponse와 같은 모양).
// code는 API에 보내는 값, name은 화면용 한글 이름. 순서는 enum 선언 순서다.
public record VisaReviewOptionsResponse(List<Option> visaTypes, List<Option> applicationTypes, List<Option> results) {

    public record Option(String code, String name) {
    }

    public static VisaReviewOptionsResponse create() {
        return new VisaReviewOptionsResponse(
                Arrays.stream(VisaType.values()).map(v -> new Option(v.name(), v.getLabel())).toList(),
                Arrays.stream(VisaApplicationType.values()).map(v -> new Option(v.name(), v.getLabel())).toList(),
                Arrays.stream(VisaResult.values()).map(v -> new Option(v.name(), v.getLabel())).toList());
    }
}
