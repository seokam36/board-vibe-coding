package com.example.demo.visa.controller;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaType;
import com.example.demo.visa.dto.VisaReviewRequest;
import com.example.demo.visa.dto.VisaReviewResponse;
import com.example.demo.visa.dto.VisaReviewStatResponse;
import com.example.demo.visa.service.VisaReviewService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

// 비자 후기 HTTP 요청을 받는 진입점 (/visa-reviews)
@RestController
@RequestMapping("/visa-reviews")
@RequiredArgsConstructor
public class VisaReviewController {

    private final VisaReviewService visaReviewService;

    // 작성: 201 Created + Location 헤더로 새 후기 주소를 알려준다. 학교 인증 회원만 가능.
    @PostMapping
    public ResponseEntity<VisaReviewResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VisaReviewRequest request) {
        VisaReviewResponse response = visaReviewService.create(LoginUser.from(jwt), request);
        return ResponseEntity.created(URI.create("/visa-reviews/" + response.id())).body(response);
    }

    // 통계 조회 (공개). 학교 x 비자 종류 x 신청 종류별 건수·평균 처리 일수. 필터 파라미터는 목록과 같다.
    // 리터럴 경로(/stats)가 패턴(/{id})보다 우선하므로 순서와 상관없이 여기로 오지만, 읽기 쉽게 /{id}보다 위에 둔다.
    @GetMapping("/stats")
    public List<VisaReviewStatResponse> getStats(@RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) VisaType visaType,
            @RequestParam(required = false) VisaApplicationType applicationType) {
        return visaReviewService.getStats(schoolId, visaType, applicationType);
    }

    // 단건 조회 (로그인 없이 공개)
    @GetMapping("/{id}")
    public VisaReviewResponse get(@PathVariable Long id) {
        return visaReviewService.get(id);
    }

    // 목록 조회 (최신순, 공개). 세 파라미터는 선택 필터. 숫자·코드 형식이 틀리면 스프링이 400을 돌려준다.
    @GetMapping
    public List<VisaReviewResponse> getAll(@RequestParam(required = false) Long schoolId,
            @RequestParam(required = false) VisaType visaType,
            @RequestParam(required = false) VisaApplicationType applicationType) {
        return visaReviewService.getAll(schoolId, visaType, applicationType);
    }

    // 수정: 모든 필드를 새 값으로 바꾸고 200 + 수정된 후기를 돌려준다 (본인만)
    @PutMapping("/{id}")
    public VisaReviewResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id,
            @Valid @RequestBody VisaReviewRequest request) {
        return visaReviewService.update(id, LoginUser.from(jwt), request);
    }

    // 삭제: 성공하면 본문 없이 204 (본인 또는 관리자)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id) {
        visaReviewService.delete(id, LoginUser.from(jwt));
        return ResponseEntity.noContent().build();
    }
}
