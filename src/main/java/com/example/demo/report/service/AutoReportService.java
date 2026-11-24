package com.example.demo.report.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.market.domain.ImageSafetyResult;
import com.example.demo.market.domain.MarketItem;
import com.example.demo.market.domain.SafetyCategory;
import com.example.demo.market.domain.SafetyVerdict;
import com.example.demo.report.domain.Report;
import com.example.demo.report.domain.ReportReason;
import com.example.demo.report.domain.ReportSource;
import com.example.demo.report.domain.ReportStatus;
import com.example.demo.report.repository.ReportRepository;

import lombok.RequiredArgsConstructor;

// 시스템이 만드는 자동 신고 담당 (S30-b). 회원 신고(ReportService)와는 규칙이 달라 따로 둔다.
// 사유 매핑·설명 문장·"대기 중 자동 신고는 글당 1건" 규칙을 여기서만 안다.
@Service
@RequiredArgsConstructor
public class AutoReportService {

    // DB reports.detail 길이(300자)와 같다. 넘치면 "외"로 줄인다.
    static final int MAX_DETAIL = 300;

    private final ReportRepository reportRepository;

    // 애매한 중고 사진(FLAG)을 관리자 신고 목록에 올린다.
    // MANDATORY: 호출자의 트랜잭션(= 글 행을 FOR UPDATE로 잠근 안) 안에서만 돈다. 잠금이 같은 글 업로드를 줄 세우므로
    //   "대기 중 자동 신고가 있나 보고 넣기/덧붙이기"가 안전하다. 트랜잭션 없이 부르면 예외.
    // 이미 대기 중인 자동 신고가 있으면 새로 만들지 않고 그 신고의 설명에 새 사진을 덧붙인다(흔적은 남기되 목록은 1건).
    // 부분 UNIQUE 위반을 catch하는 방식은 쓰지 않는다: Postgres는 INSERT가 실패하면 트랜잭션 전체가 못 쓰게 돼 사진 INSERT까지 롤백되기 때문.
    @Transactional(propagation = Propagation.MANDATORY)
    public void reportMarketItemPhoto(MarketItem item, Long photoId, ImageSafetyResult result) {
        // FLAG가 아니거나 항목·등급이 없으면 할 일이 없다
        if (result.verdict() != SafetyVerdict.FLAG || result.category() == null || result.likelihood() == null) {
            return;
        }
        String entry = "사진 " + photoId + ", " + label(result.category()) + " " + result.likelihood().name();
        reportRepository
                .findFirstByMarketItemIdAndSourceAndStatus(item.getId(), ReportSource.AUTO, ReportStatus.PENDING)
                .ifPresentOrElse(existing -> existing.appendAutoDetail(entry, MAX_DETAIL),
                        () -> reportRepository.save(Report.ofMarketItemAuto(item, reason(result.category()),
                                "자동 탐지: " + entry)));
    }

    // 자동 신고 사유: 성인물·선정성 -> 음란·선정적, 폭력 -> 기타 (기존 사유 값을 재사용, 새 사유는 만들지 않는다)
    private static ReportReason reason(SafetyCategory category) {
        return category == SafetyCategory.VIOLENCE ? ReportReason.OTHER : ReportReason.SEXUAL;
    }

    private static String label(SafetyCategory category) {
        return switch (category) {
            case ADULT -> "성인물";
            case VIOLENCE -> "폭력";
            case RACY -> "선정성";
        };
    }
}
