package com.example.demo.permit.controller;

import java.util.List;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.permit.domain.PermitStatus;
import com.example.demo.permit.dto.AdminPermitRequestResponse;
import com.example.demo.permit.dto.PermitApproveRequest;
import com.example.demo.permit.dto.PermitPhotoContent;
import com.example.demo.permit.dto.PermitRejectRequest;
import com.example.demo.permit.service.AdminPermitService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 관리자 허가서 심사 진입점 (/admin/permit-requests). 1차 관문은 SecurityConfig(토큰 role이 ADMIN이어야 함), 2차는 서비스의 DB 재확인.
@RestController
@RequestMapping("/admin/permit-requests")
@RequiredArgsConstructor
public class AdminPermitController {

    private final AdminPermitService adminPermitService;

    // 신청 목록(오래된 순). status 기본값 PENDING, 모르는 값이면 스프링이 400.
    @GetMapping
    public List<AdminPermitRequestResponse> getAll(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "PENDING") PermitStatus status) {
        return adminPermitService.getAll(status, LoginUser.from(jwt));
    }

    // 허가서 사진 보기. 개인정보라 캐시 금지(no-store), 브라우저가 종류를 멋대로 바꿔 읽지 못하게 nosniff.
    @GetMapping("/{id}/photo")
    public ResponseEntity<InputStreamResource> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        PermitPhotoContent content = adminPermitService.getPhoto(id, LoginUser.from(jwt));
        // 스트림이 이미 열린 상태다. 응답을 조립하다 예외가 나면 Spring이 닫아 주지 못하니 여기서 닫고 다시 던진다.
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(content.contentType()))
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .header("X-Content-Type-Options", "nosniff")
                    .contentLength(content.photo().length())
                    .body(new InputStreamResource(content.photo().stream()));
        } catch (RuntimeException | Error e) {
            try {
                content.photo().stream().close();
            } catch (java.io.IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    // 승인: body {schoolId, permitNumber}. 200 + 처리된 항목
    @PutMapping("/{id}/approve")
    public AdminPermitRequestResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody PermitApproveRequest body) {
        return adminPermitService.approve(id, body, LoginUser.from(jwt));
    }

    // 반려: body {reason}. 200 + 처리된 항목
    @PutMapping("/{id}/reject")
    public AdminPermitRequestResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody PermitRejectRequest body) {
        return adminPermitService.reject(id, body.reason(), LoginUser.from(jwt));
    }
}
