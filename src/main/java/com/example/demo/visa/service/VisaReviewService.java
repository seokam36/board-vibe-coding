package com.example.demo.visa.service;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.school.dto.SchoolResponse;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.visa.domain.VisaApplicationType;
import com.example.demo.visa.domain.VisaReview;
import com.example.demo.visa.domain.VisaType;
import com.example.demo.visa.dto.VisaOptionResponse;
import com.example.demo.visa.dto.VisaReviewRequest;
import com.example.demo.visa.dto.VisaReviewResponse;
import com.example.demo.visa.dto.VisaReviewStatResponse;
import com.example.demo.visa.repository.VisaReviewRepository;
import com.example.demo.visa.repository.VisaReviewFilter;
import com.example.demo.visa.repository.VisaReviewStatRow;

import lombok.RequiredArgsConstructor;

// 비자 후기 비즈니스 로직. open-in-view=false이므로 엔티티→DTO 변환을 여기서 끝낸다.
@Service
@RequiredArgsConstructor
public class VisaReviewService {

    // 작성자 탈퇴로 깨지는 FK 제약 이름 (V7). 이 이름일 때만 401로 바꾼다.
    private static final String AUTHOR_FK = "fk_visa_reviews_author";

    private final VisaReviewRepository visaReviewRepository;
    private final SchoolRepository schoolRepository; // 목록의 학교 조건이 실제 있는 학교인지 확인
    private final AccessGuard accessGuard;
    private final Clock clock; // "오늘"(서울 기준)을 알려 준다. 테스트에서 고정 시계로 바꿔 끼울 수 있다.

    // 후기를 저장하고 결과를 돌려준다. 작성자·학교는 요청 본문이 아니라 로그인 회원의 인증 정보에서만 가져온다.
    // 검사 순서(글쓰기 PostService.create와 같은 흐름): 회원 없음 401 -> 미인증 403 -> 날짜 400.
    // 누가 쓸 수 있는지(401·403)를 먼저 보고, 그다음 입력 내용(400)을 본다. 그래야 미인증 회원에게
    // 입력 규칙을 알려 주지 않고, 글쓰기·댓글 등 다른 쓰기 API와 순서가 같아 프론트가 일관되게 처리한다.
    @Transactional
    public VisaReviewResponse create(LoginUser loginUser, VisaReviewRequest request) {
        User author = accessGuard.requireVerified(loginUser);
        validateDates(request.receivedDate(), request.resultDate());
        String content = contentOrEmpty(request);
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

    // 목록을 최신순으로 조회 (페이징 없음). schoolId·visaType·applicationType은 선택이며, 넘긴 조건만 AND로 건다.
    // 없는 학교면 404(글 목록 PostService.getAll과 같다: 학교 확인 쿼리 +1). 조건을 안 건 항목은 WHERE에 넣지 않는다.
    // 작성자·학교는 repository의 EntityGraph가 join으로 같이 읽으므로 후기 수와 무관하게 목록 쿼리는 1번이다.
    @Transactional(readOnly = true)
    public List<VisaReviewResponse> getAll(Long schoolId, VisaType visaType, VisaApplicationType applicationType) {
        VisaReviewFilter filter = checkedFilter(schoolId, visaType, applicationType);
        return visaReviewRepository.findAll(filter.toSpecification(), Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(VisaReviewResponse::from)
                .toList();
    }

    // 통계(누구나): 학교 x 비자 종류 x 신청 종류 조합별 후기 건수와 평균 처리 일수(불허 건 포함).
    // 쿼리는 집계 1번(+ schoolId를 넘기면 학교 확인 1번). 후기가 없으면 빈 목록.
    // 정렬은 학교 이름 가나다 -> 학교 id -> 비자 종류 선언 순 -> 신청 종류 선언 순.
    // 이름 정렬을 DB가 아니라 자바에서 하는 이유는 SchoolService.getAll과 같다(DB 언어 설정에 따라 한글 순서가 달라짐).
    @Transactional(readOnly = true)
    public List<VisaReviewStatResponse> getStats(Long schoolId, VisaType visaType, VisaApplicationType applicationType) {
        VisaReviewFilter filter = checkedFilter(schoolId, visaType, applicationType);
        return visaReviewRepository.aggregate(filter).stream()
                .sorted(Comparator.comparing(VisaReviewStatRow::schoolName)
                        .thenComparing(VisaReviewStatRow::schoolId)
                        .thenComparing(VisaReviewStatRow::visaType) // enum은 선언 순서로 비교된다
                        .thenComparing(VisaReviewStatRow::applicationType))
                .map(r -> new VisaReviewStatResponse(
                        new SchoolResponse(r.schoolId(), r.schoolName()),
                        VisaOptionResponse.from(r.visaType()),
                        VisaOptionResponse.from(r.applicationType()),
                        r.count(),
                        // 평균을 소수 1자리로 반올림 (12.25 -> 12.3)
                        r.avgDays().setScale(1, RoundingMode.HALF_UP).doubleValue()))
                .toList();
    }

    // 목록·통계가 같이 쓰는 거르기 조건 만들기. schoolId를 넘겼는데 없는 학교면 404.
    private VisaReviewFilter checkedFilter(Long schoolId, VisaType visaType, VisaApplicationType applicationType) {
        if (schoolId != null && !schoolRepository.existsById(schoolId)) {
            throw ApiErrors.schoolNotFound();
        }
        return new VisaReviewFilter(schoolId, visaType, applicationType);
    }

    // 후기 수정(본인만). 검사 순서는 글 수정 PostService.update와 같다: 입력 400(날짜) -> 없는 id 404 -> 본인 아님 403.
    // (본문 형식 400은 컨트롤러 @Valid가 이미 먼저 걸렀다.) 관리자도 남의 후기는 못 고친다. 학교 인증은 요구하지 않는다(S10 규칙).
    // 영속 상태 엔티티를 바꾸면 트랜잭션 종료 시 자동으로 UPDATE 된다(dirty checking). 학교·작성자·작성 시각은 안 바뀐다.
    @Transactional
    public VisaReviewResponse update(Long id, LoginUser loginUser, VisaReviewRequest request) {
        validateDates(request.receivedDate(), request.resultDate());
        VisaReview review = visaReviewRepository.findById(id).orElseThrow(ApiErrors::visaReviewNotFound);
        if (!review.isWrittenBy(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인이 쓴 후기만 수정할 수 있습니다.");
        }
        review.update(request.visaType(), request.applicationType(), request.receivedDate(), request.resultDate(),
                request.result(), contentOrEmpty(request));
        return VisaReviewResponse.from(review);
    }

    // 후기 삭제(DB 행 제거). 없으면 404, 본인도 관리자도 아니면 403. (PostService.delete와 같은 패턴)
    @Transactional
    public void delete(Long id, LoginUser loginUser) {
        VisaReview review = visaReviewRepository.findById(id).orElseThrow(ApiErrors::visaReviewNotFound);
        // 관리자 토큰이면 DB에서 role을 다시 확인한다(회원 없음 401, 강등 403). 작성자가 없는 후기는 관리자만 지울 수 있다.
        accessGuard.checkOwnerOrAdmin(review.isWrittenBy(loginUser.id()), loginUser, "본인이 쓴 후기만 삭제할 수 있습니다.");
        // 권한 확인 뒤 동시에 다른 쪽이 먼저 지웠으면 0행이므로 404 (예외 500 방지)
        if (visaReviewRepository.deleteByIdReturningCount(id) == 0) {
            throw ApiErrors.visaReviewNotFound();
        }
    }

    // 본문은 선택이라 없으면 빈 문자열로 저장한다(DB 컬럼이 NOT NULL)
    private static String contentOrEmpty(VisaReviewRequest request) {
        return request.content() == null ? "" : request.content();
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
