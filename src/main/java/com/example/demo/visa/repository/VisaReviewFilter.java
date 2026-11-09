package com.example.demo.visa.repository;

import org.springframework.data.jpa.domain.Specification;

import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaReview;
import com.example.demo.visa.domain.VisaType;

// 후기 거르기 조건 세 가지(목록·통계가 같이 쓴다). 각 값이 null이면 그 조건은 안 건 것이다.
// repository 패키지에 두는 이유: 저장소가 서비스를 import하는 역방향 의존을 피하려고. 학교가 실제로 있는지는 VisaReviewService.checkedFilter가 확인한 뒤에 만든다.
public record VisaReviewFilter(Long schoolId, VisaType visaType, VisaApplicationType applicationType) {

    // 목록 조회용: 넘긴 조건만 AND로 건 Specification
    public Specification<VisaReview> toSpecification() {
        Specification<VisaReview> spec = Specification.unrestricted();
        if (schoolId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("school").get("id"), schoolId));
        }
        if (visaType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("visaType"), visaType));
        }
        if (applicationType != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("applicationType"), applicationType));
        }
        return spec;
    }
}
