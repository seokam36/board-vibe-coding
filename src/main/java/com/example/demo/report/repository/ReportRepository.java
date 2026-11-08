package com.example.demo.report.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.report.domain.Report;

// 신고 DB 접근 담당
public interface ReportRepository extends JpaRepository<Report, Long> {

    // 이 회원이 이 글을 이미 신고했는지
    boolean existsByPostIdAndReporterId(Long postId, Long reporterId);

    // 이 회원이 이 댓글을 이미 신고했는지
    boolean existsByCommentIdAndReporterId(Long commentId, Long reporterId);
}
