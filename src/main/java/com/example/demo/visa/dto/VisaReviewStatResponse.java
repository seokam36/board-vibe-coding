package com.example.demo.visa.dto;

import com.example.demo.school.dto.SchoolResponse;

// 통계 한 줄: (학교 x 비자 종류 x 신청 종류) 조합 하나의 후기 건수와 평균 처리 일수.
// avgDays는 소수 1자리 반올림이고, 불허 건도 평균에 포함한다.
public record VisaReviewStatResponse(SchoolResponse school, VisaOptionResponse visaType,
        VisaOptionResponse applicationType, long count, double avgDays) {
}
