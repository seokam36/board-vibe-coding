package com.example.demo.permit.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.permit.dto.PermitStatusResponse;
import com.example.demo.permit.service.PermitVerificationService;

import lombok.RequiredArgsConstructor;

// 표준입학허가서 인증 신청 HTTP 요청을 받는 진입점 (/users/me/permit-verification). 로그인 필요(SecurityConfig의 anyRequest().authenticated()).
@RestController
@RequestMapping("/users/me/permit-verification")
@RequiredArgsConstructor
public class PermitVerificationController {

    private final PermitVerificationService permitService;

    // 신청하기: multipart 파트 "file"(허가서 사진 한 장) + "consent"(true). 201 + 상태 본문.
    // multipart가 아닌 요청은 스프링이 415로 처리한다. 파일 크기 한도(10MB/요청 11MB)는 전역 설정(application.properties)이라 넘으면 413.
    // file·consent는 required=false로 받아 서비스가 "없음 -> 400"으로 직접 처리한다(검사 순서를 한 곳에서 관리).
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PermitStatusResponse> submit(@AuthenticationPrincipal Jwt jwt,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "consent", required = false) Boolean consent) {
        return ResponseEntity.status(HttpStatus.CREATED).body(permitService.submit(LoginUser.from(jwt), file, consent));
    }

    // 내 상태 보기: 200 {status, rejectReason?, requestedAt?, decidedAt?}
    @GetMapping
    public PermitStatusResponse status(@AuthenticationPrincipal Jwt jwt) {
        return permitService.getStatus(LoginUser.from(jwt));
    }

    // 철회/인증 해제: 204 본문 없음
    @DeleteMapping
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal Jwt jwt) {
        permitService.cancel(LoginUser.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
