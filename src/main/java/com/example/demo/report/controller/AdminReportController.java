package com.example.demo.report.controller;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.report.domain.ReportStatus;
import com.example.demo.report.dto.AdminConversationResponse;
import com.example.demo.report.dto.AdminReportResponse;
import com.example.demo.report.service.AdminReportService;

import lombok.RequiredArgsConstructor;

// 관리자 신고 처리 진입점 (/admin/**). 1차 관문은 SecurityConfig(토큰 role이 ADMIN이어야 함), 2차는 서비스의 DB 재확인.
@RestController
@RequestMapping("/admin/reports")
@RequiredArgsConstructor
public class AdminReportController {

    private final AdminReportService adminReportService;

    // 신고 목록. status 기본값 PENDING. 모르는 값이면 스프링이 400을 돌려준다.
    @GetMapping
    public List<AdminReportResponse> getAll(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "PENDING") ReportStatus status) {
        return adminReportService.getAll(status, LoginUser.from(jwt));
    }

    // 신고된 대화 읽기(대화 신고만). 200 + 방 전체 쪽지. 읽음 처리는 하지 않는다.
    @GetMapping("/{id}/conversation")
    public AdminConversationResponse getConversation(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return adminReportService.getConversation(id, LoginUser.from(jwt));
    }

    // 기각 (본문 없음). 200 + 기각된 신고
    @PutMapping("/{id}/dismiss")
    public AdminReportResponse dismiss(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        return adminReportService.dismiss(id, LoginUser.from(jwt));
    }
}
