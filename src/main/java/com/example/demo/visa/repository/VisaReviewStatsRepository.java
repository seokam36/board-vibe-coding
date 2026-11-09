package com.example.demo.visa.repository;

import java.util.List;


// 통계 집계용 조각(fragment). VisaReviewRepository가 이 인터페이스도 상속하고,
// 구현은 이름 규칙에 따라 VisaReviewStatsRepositoryImpl이 맡는다(스프링 데이터가 자동으로 연결).
public interface VisaReviewStatsRepository {

    // 학교 x 비자 종류 x 신청 종류로 묶어 건수와 평균 처리 일수를 구한다. 후기가 없는 조합은 나오지 않는다.
    // 정렬은 하지 않는다(DB 한글 정렬이 가나다와 다를 수 있어 서비스가 자바에서 정렬).
    List<VisaReviewStatRow> aggregate(VisaReviewFilter filter);
}
