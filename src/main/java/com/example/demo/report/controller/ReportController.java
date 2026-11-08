package com.example.demo.report.controller;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.report.domain.ReportReason;
import com.example.demo.report.dto.ReportCreateRequest;
import com.example.demo.report.dto.ReportReasonResponse;
import com.example.demo.report.dto.ReportResponse;
import com.example.demo.report.service.ReportService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 신고 HTTP 요청을 받는 진입점. 사유 목록은 공개, 신고하기는 로그인 필요.
@RestController
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    // 사유 목록 (공개). enum 고정값이라 DB 조회 없이 바로 돌려준다.
    @GetMapping("/report-reasons")
    public List<ReportReasonResponse> getReasons() {
        return Arrays.stream(ReportReason.values()).map(ReportReasonResponse::from).toList();
    }

    // 글 신고: 201. 신고 한 건을 따로 조회하는 API가 없어서 Location 헤더는 붙이지 않는다.
    @PostMapping("/posts/{postId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse reportPost(@AuthenticationPrincipal Jwt jwt, @PathVariable Long postId,
            @Valid @RequestBody ReportCreateRequest request) {
        return reportService.reportPost(postId, LoginUser.from(jwt), request);
    }

    // 댓글 신고: 201
    @PostMapping("/comments/{id}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public ReportResponse reportComment(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody ReportCreateRequest request) {
        return reportService.reportComment(id, LoginUser.from(jwt), request);
    }
}
