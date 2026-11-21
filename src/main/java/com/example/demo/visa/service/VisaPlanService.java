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
import com.example.demo.push.repository.VisaPlanNotificationRepository;
import com.example.demo.user.domain.User;
import com.example.demo.visa.domain.VisaPlan;
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
    private final VisaPlanNotificationRepository notificationRepository;
    private final AccessGuard accessGuard;
    private final Clock clock; // "오늘"(서울 기준). 테스트에서 고정 시계로 바꿔 끼울 수 있다.

    // 내 일정 조회. 일정이 없으면 404가 아니라 expiryDate가 null인 응답(화면이 매번 불러도 되게).
    // 학교 인증은 요구하지 않는다. 회원이 DB에 없으면(유령 토큰) 다른 API와 같이 401.
    @Transactional(readOnly = true)
    public VisaPlanResponse get(LoginUser loginUser) {
        User user = accessGuard.requireUser(loginUser);
        // "오늘"은 요청당 한 번만 구해 응답 계산에 넘긴다 (서울 기준)
        LocalDate today = LocalDate.now(clock);
        LocalDate expiry = visaPlanRepository.findByUserId(user.getId()).map(VisaPlan::getExpiryDate).orElse(null);
        return VisaPlanResponse.of(expiry, today);
    }

    // 일정 등록 또는 덮어쓰기(회원당 1행). 검사 순서는 후기 작성과 같다:
    // 회원 없음 401 -> 학교 미인증 403 -> 날짜 범위 400 (누가 할 수 있는지를 먼저, 입력 내용은 그다음).
    @Transactional
    public VisaPlanResponse save(LoginUser loginUser, VisaPlanRequest request) {
        User user = accessGuard.requireVerified(loginUser);
        LocalDate today = LocalDate.now(clock); // 검증과 응답 계산이 같은 "오늘"을 쓰게 한 번만 구함
        validateExpiryDate(request.expiryDate(), today);
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
        // upsert 뒤 엔티티를 다시 읽지 않고(영속성 컨텍스트의 옛 값 문제 회피) 요청 값으로 바로 계산
        return VisaPlanResponse.of(request.expiryDate(), today);
    }

    // 일정 삭제. 지울 게 없으면 404. 학교 인증은 요구하지 않는다(이미 있는 일정을 치우는 것이므로).
    @Transactional
    public void delete(LoginUser loginUser) {
        User user = accessGuard.requireUser(loginUser);
        if (visaPlanRepository.deleteByUserIdReturningCount(user.getId()) == 0) {
            throw ApiErrors.visaPlanNotFound();
        }
        // 일정을 지우면 만료일이 담긴 보낸 기록도 같이 지운다(탈퇴 전까지 남지 않게). 같은 트랜잭션이라 둘 다 되거나 둘 다 안 된다.
        // 잠금 순서: 일정 행 -> 기록 행. 푸시 쪽은 회원 행 -> 기록 행이라 서로 거꾸로 기다리는 일이 없다.
        // 탈퇴와의 관계: 탈퇴는 회원 행 FOR UPDATE 뒤 CASCADE로 일정 -> 기록 순서로 지운다. 이 순서는 PostgreSQL이 FK 트리거를
        // 실행하는 순서(제약 이름/생성 순)에 기대는 것이라 백업 복원 뒤엔 뒤집힐 수 있다. 그러면 같은 회원이 탈퇴와 일정 삭제를
        // 동시에 할 때 아주 드물게 교착으로 500이 날 수 있다(데이터는 안 깨짐). 확률이 매우 낮아 회원 행 잠금은 일부러 넣지 않았다.
        // 지운 뒤 같은 날짜로 다시 등록하면 그 단계 알림이 한 번 더 갈 수 있지만, 일부러 다시 등록한 것이라 허용한다.
        notificationRepository.deleteByUserId(user.getId());
    }

    // 만료일은 서울 오늘 이상, 오늘+5년 이하. 어기면 400. 경계(오늘, 오늘+5년 정확히)는 통과.
    // LocalDate.now()가 아니라 clock을 쓰는 이유: 서버는 UTC라 서울 밤에 "오늘"이 하루 어긋난다.
    private void validateExpiryDate(LocalDate expiryDate, LocalDate today) {
        if (expiryDate.isBefore(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료일은 오늘 이후여야 합니다.");
        }
        if (expiryDate.isAfter(today.plusYears(MAX_YEARS_AHEAD))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "만료일은 오늘부터 5년 이내여야 합니다.");
        }
    }
}
