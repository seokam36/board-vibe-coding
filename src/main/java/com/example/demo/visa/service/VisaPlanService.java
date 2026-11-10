package com.example.demo.visa.service;

import java.time.Clock;
import java.time.LocalDate;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.user.domain.User;
import com.example.demo.visa.dto.VisaPlanRequest;
import com.example.demo.visa.dto.VisaPlanResponse;
import com.example.demo.visa.repository.VisaPlanRepository;

import lombok.RequiredArgsConstructor;

// 내 비자 일정(체류 만료일 하나) 비즈니스 로직. open-in-view=false이므로 엔티티->DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor
public class VisaPlanService {

    // 회원 삭제(탈퇴)로 깨지는 FK 제약 이름 (V8). 이 이름일 때만 401로 바꾼다.
    private static final String USER_FK = "fk_visa_plans_user";

    // 만료일은 오늘부터 최대 5년 뒤까지만 받는다 (엉뚱한 먼 미래 날짜를 막음)
    private static final int MAX_YEARS_AHEAD = 5;

    private final VisaPlanRepository visaPlanRepository;
    private final AccessGuard accessGuard;
    private final Clock clock; // "오늘"(서울 기준). 테스트에서 고정 시계로 바꿔 끼울 수 있다.

    // 내 일정 조회. 일정이 없으면 404가 아니라 expiryDate가 null인 응답(화면이 매번 불러도 되게).
    // 학교 인증은 요구하지 않는다. 회원이 DB에 없으면(유령 토큰) 다른 API와 같이 401.
    @Transactional(readOnly = true)
    public VisaPlanResponse get(LoginUser loginUser) {
        User user = accessGuard.requireUser(loginUser);
        return visaPlanRepository.findByUserId(user.getId())
                .map(VisaPlanResponse::from)
                .orElseGet(VisaPlanResponse::empty);
    }

    // 일정 등록 또는 덮어쓰기(회원당 1행). 검사 순서는 후기 작성과 같다:
    // 회원 없음 401 -> 학교 미인증 403 -> 날짜 범위 400 (누가 할 수 있는지를 먼저, 입력 내용은 그다음).
    @Transactional
    public VisaPlanResponse save(LoginUser loginUser, VisaPlanRequest request) {
        User user = accessGuard.requireVerified(loginUser);
        validateExpiryDate(request.expiryDate());
        try {
            // "없으면 INSERT, 있으면 UPDATE"를 SQL 한 문장으로 처리 -> 동시 PUT도 안전
            visaPlanRepository.upsert(user.getId(), request.expiryDate());
        } catch (DataIntegrityViolationException e) {
            // 회원 조회 직후 탈퇴가 커밋되면 user FK가 위반된다 -> "회원이 없음"과 같이 401. 다른 위반은 그대로 던진다.
            if (ConstraintViolations.violatedConstraint(e).filter(USER_FK::equals).isPresent()) {
                throw ApiErrors.loginRequired();
            }
            throw e;
        }
        return new VisaPlanResponse(request.expiryDate());
    }

    // 일정 삭제. 지울 게 없으면 404. 학교 인증은 요구하지 않는다(이미 있는 일정을 치우는 것이므로).
    @Transactional
    public void delete(LoginUser loginUser) {
        User user = accessGuard.requireUser(loginUser);
        if (visaPlanRepository.deleteByUserIdReturningCount(user.getId()) == 0) {
            throw ApiErrors.visaPlanNotFound();
        }
    }

    // 만료일은 서울 오늘 이상, 오늘+5년 이하. 어기면 400. 경계(오늘, 오늘+5년 정확히)는 통과.
    // LocalDate.now()가 아니라 clock을 쓰는 이유: 서버는 UTC라 서울 밤에 "오늘"이 하루 어긋난다.
    private void validateExpiryDate(LocalDate expiryDate) {
        LocalDate today = LocalDate.now(clock);
        if (expiryDate.isBefore(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료일은 오늘 이후여야 합니다.");
        }
        if (expiryDate.isAfter(today.plusYears(MAX_YEARS_AHEAD))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료일은 오늘부터 5년 이내여야 합니다.");
        }
    }
}
