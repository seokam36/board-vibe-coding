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
import com.example.demo.visa.domain.VisaReview;
import com.example.demo.visa.dto.VisaReviewCreateRequest;
import com.example.demo.visa.dto.VisaReviewResponse;
import com.example.demo.visa.repository.VisaReviewRepository;

import lombok.RequiredArgsConstructor;

// 비자 후기 비즈니스 로직. open-in-view=false이므로 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor
public class VisaReviewService {

    // 작성자 탈퇴로 깨지는 FK 제약 이름 (V7). 이 이름일 때만 401로 바꾼다.
    private static final String AUTHOR_FK = "fk_visa_reviews_author";

    private final VisaReviewRepository visaReviewRepository;
    private final AccessGuard accessGuard;
    private final Clock clock; // "오늘"(서울 기준)을 알려 준다. 테스트에서 고정 시계로 바꿔 끼울 수 있다.

    // 후기를 저장하고 결과를 돌려준다. 작성자·학교는 요청 본문이 아니라 로그인 회원의 인증 정보에서만 가져온다.
    // 검사 순서(글쓰기 PostService.create와 같은 흐름): 회원 없음 401 -> 미인증 403 -> 날짜 400.
    // 누가 쓸 수 있는지(401·403)를 먼저 보고, 그다음 입력 내용(400)을 본다. 그래야 미인증 회원에게
    // 입력 규칙을 알려 주지 않고, 글쓰기·댓글 등 다른 쓰기 API와 순서가 같아 프론트가 일관되게 처리한다.
    @Transactional
    public VisaReviewResponse create(LoginUser loginUser, VisaReviewCreateRequest request) {
        User author = accessGuard.requireVerified(loginUser);
        validateDates(request.receivedDate(), request.resultDate());
        // 본문은 선택이라 없으면 빈 문자열로 저장한다(DB 컬럼이 NOT NULL)
        String content = request.content() == null ? "" : request.content();
        try {
            // id가 IDENTITY라 save() 안에서 바로 INSERT가 나가므로 FK 위반을 여기서 잡을 수 있다
            VisaReview saved = visaReviewRepository.save(new VisaReview(author, author.getVerifiedSchool(),
                    request.visaType(), request.applicationType(), request.receivedDate(), request.resultDate(),
                    request.result(), content));
            return VisaReviewResponse.from(saved);
        } catch (DataIntegrityViolationException e) {
            // 회원 조회 직후 탈퇴가 커밋되면 author FK가 위반된다 -> "회원이 없음"과 같이 401.
            // 다른 제약 위반은 숨기지 않고 그대로 던진다.
            if (ConstraintViolations.violatedConstraint(e).filter(AUTHOR_FK::equals).isPresent()) {
                throw ApiErrors.loginRequired();
            }
            throw e;
        }
    }

    // 후기 한 건 조회(누구나). 없으면 404
    @Transactional(readOnly = true)
    public VisaReviewResponse get(Long id) {
        return VisaReviewResponse.from(visaReviewRepository.findById(id)
                .orElseThrow(ApiErrors::visaReviewNotFound));
    }

    // 결과일은 접수일보다 앞설 수 없고, 두 날짜 모두 오늘(서울 기준)을 넘을 수 없다. 어기면 400.
    private void validateDates(LocalDate receivedDate, LocalDate resultDate) {
        LocalDate today = LocalDate.now(clock);
        if (resultDate.isBefore(receivedDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "결과일은 접수일보다 빠를 수 없습니다.");
        }
        if (receivedDate.isAfter(today) || resultDate.isAfter(today)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "오늘 이후 날짜는 쓸 수 없습니다.");
        }
    }
}
