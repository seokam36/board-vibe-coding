package com.example.demo.visa.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.visa.domain.VisaReview;

// 비자 후기 저장소. S11-a에서는 메서드가 없고, 작성·목록·통계 단계에서 하나씩 추가한다.
public interface VisaReviewRepository extends JpaRepository<VisaReview, Long> {
}
