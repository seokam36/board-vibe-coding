package com.example.demo.visa.repository;

import java.math.BigDecimal;

import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaType;

// avgDays를 BigDecimal로 두는 이유: double을 거치면 12.25 같은 값이 12.2499..로 어긋나 반올림이 틀릴 수 있어서.
// 집계 쿼리 결과 한 줄(DB에서 막 꺼낸 값). 반올림·정렬·DTO 변환은 서비스가 한다.
public record VisaReviewStatRow(Long schoolId, String schoolName, VisaType visaType,
        VisaApplicationType applicationType, long count, BigDecimal avgDays) {
}
