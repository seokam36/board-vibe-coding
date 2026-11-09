package com.example.demo.visa.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.user.dto.AuthorResponse;
import com.example.demo.visa.domain.VisaReview;

// 비자 후기 응답. 엔티티를 그대로 내보내지 않는다.
// author는 {id, nickname} 또는 null(작성자가 탈퇴), school은 {id, name}, 나머지 선택값은 {code, name}.
// processingDays(처리 일수)는 표에 저장하지 않고 접수일~결과일로 그때그때 계산한다.
public record VisaReviewResponse(Long id, AuthorResponse author, SchoolResponse school, VisaOptionResponse visaType,
        VisaOptionResponse applicationType, LocalDate receivedDate, LocalDate resultDate, VisaOptionResponse result,
        long processingDays,
        String content, Instant createdAt) {

    public static VisaReviewResponse from(VisaReview review) {
        AuthorResponse author = review.getAuthor() == null ? null : AuthorResponse.from(review.getAuthor());
        return new VisaReviewResponse(review.getId(), author, SchoolResponse.from(review.getSchool()),
                VisaOptionResponse.from(review.getVisaType()),
                VisaOptionResponse.from(review.getApplicationType()), review.getReceivedDate(),
                review.getResultDate(), VisaOptionResponse.from(review.getResult()),
                ChronoUnit.DAYS.between(review.getReceivedDate(), review.getResultDate()), review.getContent(),
                review.getCreatedAt());
    }
}
