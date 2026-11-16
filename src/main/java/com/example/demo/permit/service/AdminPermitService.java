package com.example.demo.permit.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.permit.domain.PermitRejectReason;
import com.example.demo.permit.domain.PermitRequest;
import com.example.demo.permit.domain.PermitStatus;
import com.example.demo.permit.dto.AdminPermitRequestResponse;
import com.example.demo.permit.dto.PermitApproveRequest;
import com.example.demo.permit.dto.PermitPhotoContent;
import com.example.demo.permit.repository.PermitRequestRepository;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.StoredPhoto;
import com.example.demo.photo.service.PhotoDeleteQueue;
import com.example.demo.school.domain.School;
import com.example.demo.school.repository.SchoolRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 관리자 허가서 심사 (S20-b): 목록·사진 보기·승인·반려. 토큰의 ADMIN은 최대 15분 묵은 정보라 매번 DB로 재확인한다.
// 허가서 사진·번호는 개인정보라 로그에는 "누가 어느 신청을 처리했는지"(adminId, requestId)만 남긴다.
//
// 잠금 순서(중요): 신청자 회원 행 FOR UPDATE -> 신청 행 FOR UPDATE -> 상태 재확인.
//   철회(PermitVerificationService.cancel)·탈퇴가 "회원 행 FOR UPDATE -> 신청 행 삭제" 순서라서,
//   여기서 반대로(신청 행 먼저) 잡으면 서로 기다리는 교착(40P01 -> 500)이 난다. 같은 순서로 잡으면 줄만 서고 고리가 안 생긴다.
//   신청자 id는 잠금 전에 가볍게 읽어 알아낸 뒤(잠금 없이), 잠그고 나서 신청 행을 다시 읽어 PENDING인지 확인한다.
//   회원을 잠그려는데 행이 없으면(탈퇴로 신청도 CASCADE로 지워짐) 404.
// 사진 삭제는 S20-a의 철회와 같다: 결정 트랜잭션 안에서 신청 행의 photo_key를 NULL로 바꾸며 그 key를 "지울 파일 대기 표"에 적고,
//   커밋 뒤에 파일을 지운다(실패해도 응답은 그대로, 표에 남아 청소부가 처리).
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPermitService {

    // DB 제약 이름 (V14). 깨진 제약 이름으로 오류 종류를 구분한다.
    static final String UNIQUE_PERMIT_HASH = "uk_permit_requests_permit_hash";
    static final String FK_DECIDER = "fk_permit_requests_decider";

    // 목록 한 번에 돌려주는 최대 개수 (페이징은 기존 관리자 신고 목록처럼 없다. 상한만 둔다)
    static final int LIST_LIMIT = 200;

    private final PermitRequestRepository permitRequestRepository;
    private final UserRepository userRepository;
    private final SchoolRepository schoolRepository;
    private final AccessGuard accessGuard;
    private final PhotoStorage photoStorage;
    private final PhotoDeleteQueue photoDeleteQueue;
    private final PlatformTransactionManager txManager;
    private final PermitNumberHash permitNumberHash;

    // 상태별 신청 목록(오래된 순). 읽기만 하므로 잠금 없음.
    @Transactional(readOnly = true)
    public List<AdminPermitRequestResponse> getAll(PermitStatus status, LoginUser loginUser) {
        accessGuard.requireAdmin(loginUser);
        // 대기는 오래된 순(처리 순서), 처리 끝난 목록(승인·반려)은 최신순
        List<PermitRequest> requests = status == PermitStatus.PENDING
                ? permitRequestRepository.findByStatusOrderByIdAsc(status, Limit.of(LIST_LIMIT))
                : permitRequestRepository.findByStatusOrderByIdDesc(status, Limit.of(LIST_LIMIT));
        // 회원·학교는 한 번에 모아 읽는다(건마다 쿼리가 나가지 않게)
        Set<Long> userIds = new HashSet<>();
        Set<Long> schoolIds = new HashSet<>();
        for (PermitRequest r : requests) {
            userIds.add(r.getUserId());
            if (r.getSchoolId() != null) {
                schoolIds.add(r.getSchoolId());
            }
            if (r.getDetectedSchoolId() != null) {
                schoolIds.add(r.getDetectedSchoolId());
            }
        }
        Map<Long, String> nicknames = new HashMap<>();
        userRepository.findAllById(userIds).forEach(u -> nicknames.put(u.getId(), u.getNickname()));
        Map<Long, String> schoolNames = new HashMap<>();
        schoolRepository.findAllById(schoolIds).forEach(s -> schoolNames.put(s.getId(), s.getName()));
        return requests.stream()
                .map(r -> AdminPermitRequestResponse.of(r, nicknames.get(r.getUserId()),
                        schoolNames.get(r.getSchoolId()), schoolNames.get(r.getDetectedSchoolId())))
                .toList();
    }

    // 허가서 사진 열기. 검사 순서: 관리자 재확인 -> 없는 신청 404 -> 사진 없음(처리 끝·삭제됨) 404.
    // 스트림은 호출한 쪽(컨트롤러)이 닫는다. 잠금은 잡지 않는다(읽기만).
    public PermitPhotoContent getPhoto(Long id, LoginUser loginUser) {
        accessGuard.requireAdmin(loginUser);
        PermitRequest request = permitRequestRepository.findById(id).orElseThrow(AdminPermitService::notFound);
        String key = request.getPhotoKey();
        if (key == null || request.getContentType() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "허가서 사진이 없습니다.");
        }
        StoredPhoto photo = photoStorage.open(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "허가서 사진이 없습니다."));
        return new PermitPhotoContent(request.getContentType(), photo);
    }

    // 승인. 검사 순서: 관리자 재확인 401/403 -> 번호에 쓸 글자 없음 400 -> 없는 신청 404 -> 없는 학교 404
    // -> (잠금 뒤) 신청자 탈퇴 404 / 이미 처리됨 409 / 이미 인증된 회원 409 -> (커밋 때) 같은 번호가 이미 승인됨 409
    public AdminPermitRequestResponse approve(Long id, PermitApproveRequest body, LoginUser loginUser) {
        accessGuard.requireAdmin(loginUser);
        String normalized = PermitNumberHash.normalize(body.permitNumber());
        if (normalized.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허가서 번호를 입력해 주세요.");
        }
        String hash = permitNumberHash.of(normalized);

        List<String> photoKeys = new ArrayList<>();
        AdminPermitRequestResponse result;
        try {
            result = new TransactionTemplate(txManager).execute(status -> {
                Long userId = permitRequestRepository.findUserIdById(id).orElseThrow(AdminPermitService::notFound);
                rejectSelfReview(userId, loginUser);
                School school = schoolRepository.findById(body.schoolId()).orElseThrow(ApiErrors::schoolNotFound);
                LockedRequest locked = lockPending(id, userId);
                PermitRequest request = locked.request();
                User user = locked.user();
                if (user.getVerifiedSchool() != null) {
                    // 대기 중에 운영자 SQL이나 동시 요청(다른 요청의 자동 승인) 등 다른 경로로 인증됨. 관리자는 반려로 정리할 수 있다.
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 인증된 회원입니다. 반려로 정리해 주세요.");
                }
                // 같은 번호가 이미 승인돼 있으면 DB 오류를 일으키기 전에 미리 409(DB 오류 원문이 로그에 남지 않게).
                // 동시에 두 신청을 승인하는 경쟁은 이 확인을 둘 다 통과하므로 아래 부분 UNIQUE 위반(catch)이 최종 보장이다.
                if (permitRequestRepository.existsByPermitNumberHashAndStatus(hash, PermitStatus.APPROVED)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 승인된 허가서 번호입니다.");
                }
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                collectAndEnqueuePhoto(request, photoKeys);
                request.approve(school.getId(), hash, loginUser.id(), now);
                // flush: 같은 번호가 이미 승인돼 있으면 부분 UNIQUE 위반이 여기서 바로 드러난다(아래 catch가 409로 바꿈)
                permitRequestRepository.saveAndFlush(request);
                if (userRepository.markVerifiedByPermit(userId, school.getId(), now) == 0) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 인증된 회원입니다. 반려로 정리해 주세요.");
                }
                return toResponse(request, user, school.getName());
            });
        } catch (DataIntegrityViolationException e) {
            photoKeys.clear(); // 롤백됐으니 파일은 지우지 않는다
            throw translate(e);
        } catch (RuntimeException | Error e) {
            photoKeys.clear();
            throw e;
        }
        // 커밋 뒤: 파일을 지우고 지운 키를 표에서 뺀다(실패해도 예외 없이 표에 남아 청소부가 처리)
        photoDeleteQueue.deleteFilesAndDequeue(photoKeys);
        log.info("허가서 승인: adminId={}, requestId={}", loginUser.id(), id); // 번호·해시·key는 남기지 않는다
        return result;
    }

    // 반려. 검사 순서: 관리자 재확인 401/403 -> 없는 신청 404 -> (잠금 뒤) 신청자 탈퇴 404 / 이미 처리됨 409.
    // 회원은 다시 신청할 수 있다(부분 UNIQUE가 PENDING만 막는다). 이미 인증된 회원의 대기 신청도 반려로 정리할 수 있다.
    public AdminPermitRequestResponse reject(Long id, PermitRejectReason reason, LoginUser loginUser) {
        accessGuard.requireAdmin(loginUser);
        List<String> photoKeys = new ArrayList<>();
        AdminPermitRequestResponse result;
        try {
            result = new TransactionTemplate(txManager).execute(status -> {
                Long userId = permitRequestRepository.findUserIdById(id).orElseThrow(AdminPermitService::notFound);
                rejectSelfReview(userId, loginUser);
                LockedRequest locked = lockPending(id, userId);
                PermitRequest request = locked.request();
                collectAndEnqueuePhoto(request, photoKeys);
                request.reject(reason, loginUser.id(), Instant.now().truncatedTo(ChronoUnit.MICROS));
                permitRequestRepository.saveAndFlush(request);
                return toResponse(request, locked.user(), null);
            });
        } catch (DataIntegrityViolationException e) {
            photoKeys.clear();
            throw translate(e);
        } catch (RuntimeException | Error e) {
            photoKeys.clear(); // 롤백됐으니 파일은 지우지 않는다
            throw e;
        }
        photoDeleteQueue.deleteFilesAndDequeue(photoKeys);
        log.info("허가서 반려: adminId={}, requestId={}", loginUser.id(), id);
        return result;
    }

    // 기한 만료 반려 한 건 (S20-c, PermitExpiryJob이 부른다). 관리자 권한·본인 심사 검사는 없다(시스템 작업).
    // 반려와 같은 잠금 순서(회원 -> 신청 -> 재읽기)로 잡고, 여전히 PENDING이고 여전히 cutoff보다 오래됐을 때만 EXPIRED로 반려한다.
    // 그 사이 관리자가 처리했거나 회원이 철회·탈퇴했으면 건너뛰고 false. decided_by는 NULL(시스템 결정)이다.
    boolean expireIfStale(Long id, Instant cutoff, Instant now) {
        List<String> photoKeys = new ArrayList<>();
        Boolean expired;
        try {
            expired = new TransactionTemplate(txManager).execute(status -> {
                Optional<Long> userId = permitRequestRepository.findUserIdById(id);
                if (userId.isEmpty()) {
                    return false; // 이미 철회·탈퇴로 사라짐
                }
                LockedRequest locked;
                try {
                    locked = lockPending(id, userId.get());
                } catch (ResponseStatusException e) {
                    return false; // 잠그는 사이 탈퇴(404)하거나 다른 곳에서 처리됨(409)
                }
                PermitRequest request = locked.request();
                if (!request.getCreatedAt().isBefore(cutoff)) {
                    return false; // 기한이 안 지남(방어)
                }
                collectAndEnqueuePhoto(request, photoKeys);
                request.reject(PermitRejectReason.EXPIRED, null, now);
                permitRequestRepository.saveAndFlush(request);
                return true;
            });
        } catch (RuntimeException | Error e) {
            photoKeys.clear(); // 롤백됐으니 파일은 지우지 않는다
            throw e;
        }
        photoDeleteQueue.deleteFilesAndDequeue(photoKeys); // 커밋 뒤 파일 삭제(실패해도 표에 남아 청소부가 처리)
        return Boolean.TRUE.equals(expired);
    }

    // 자동 승인의 결과: 승인됨 / 같은 번호가 이미 승인돼 있음 / 승인하지 않음(신청이 사라졌거나 이미 처리됨, 이미 인증된 회원)
    enum AutoApprovalOutcome {
        APPROVED, NUMBER_DUPLICATE, NOT_APPLIED
    }

    // request는 APPROVED일 때만 채워진다(응답에 쓸 승인된 신청)
    record AutoApproval(AutoApprovalOutcome outcome, PermitRequest request) {
    }

    // 기계 자동 승인 한 건 (S20-d2, PermitAutoReview가 부른다). 관리자 권한·본인 심사 검사는 없다(시스템 작업).
    // 관리자 승인과 같은 잠금 순서(회원 -> 신청 -> PENDING 재확인)와 같은 사진 삭제 방식(트랜잭션 안에서 대기 표에 적고 커밋 뒤 삭제)을 쓴다.
    // 승인 조건이 하나라도 안 맞으면 아무것도 바꾸지 않고 돌려준다: 그 사이 철회·탈퇴·처리됐거나(NOT_APPLIED),
    // 이미 인증된 회원이거나(NOT_APPLIED), 같은 번호가 이미 승인됐으면(NUMBER_DUPLICATE). 예상 못 한 예외는 그대로 던지고, 부른 쪽(PermitVerificationService)이 삼켜 신청은 PENDING으로 남는다.
    AutoApproval autoApprove(Long id, Long userId, PermitJudgement judgement) {
        Long schoolId = judgement.detectedSchoolId();
        if (schoolId == null || judgement.normalizedNumber() == null) {
            return new AutoApproval(AutoApprovalOutcome.NOT_APPLIED, null); // 방어: 통과했다면 둘 다 있어야 한다
        }
        String hash = permitNumberHash.of(judgement.normalizedNumber());
        List<String> photoKeys = new ArrayList<>();
        AutoApproval result;
        try {
            result = new TransactionTemplate(txManager).execute(status -> {
                LockedRequest locked;
                try {
                    locked = lockPending(id, userId);
                } catch (ResponseStatusException e) {
                    return new AutoApproval(AutoApprovalOutcome.NOT_APPLIED, null); // 판독하는 사이 철회·탈퇴하거나 이미 처리됨
                }
                PermitRequest request = locked.request();
                Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
                // 같은 번호가 이미 승인돼 있으면 DB 오류를 일으키기 전에 미리 알아본다(동시 경쟁의 최종 보장은 아래 catch의 부분 UNIQUE 위반)
                if (permitRequestRepository.existsByPermitNumberHashAndStatus(hash, PermitStatus.APPROVED)) {
                    return new AutoApproval(AutoApprovalOutcome.NUMBER_DUPLICATE, null);
                }
                // 회원 칸을 먼저 채운다. 판독하는 사이 이미 인증됐으면 0행이라 아무것도 바꾸지 않고 끝낸다
                if (userRepository.markVerifiedByPermit(userId, schoolId, now) == 0) {
                    return new AutoApproval(AutoApprovalOutcome.NOT_APPLIED, null);
                }
                collectAndEnqueuePhoto(request, photoKeys);
                request.autoApprove(schoolId, hash, now);
                // flush: 같은 번호가 동시에 승인되면 부분 UNIQUE 위반이 여기서 바로 드러난다(아래 catch). 롤백되면 회원 칸 채우기도 같이 취소된다
                permitRequestRepository.saveAndFlush(request);
                return new AutoApproval(AutoApprovalOutcome.APPROVED, request);
            });
        } catch (DataIntegrityViolationException e) {
            photoKeys.clear(); // 롤백됐으니 파일은 지우지 않는다
            if (UNIQUE_PERMIT_HASH.equals(ConstraintViolations.violatedConstraint(e).orElse(""))) {
                return new AutoApproval(AutoApprovalOutcome.NUMBER_DUPLICATE, null); // 동시 승인 경쟁에서 짐
            }
            throw e;
        } catch (RuntimeException | Error e) {
            photoKeys.clear();
            throw e;
        }
        // 커밋 뒤: 파일을 지우고 지운 키를 표에서 뺀다(실패해도 예외 없이 표에 남아 청소부가 처리)
        photoDeleteQueue.deleteFilesAndDequeue(photoKeys);
        return result;
    }

    // 잠금 순서를 지키는 곳: 회원 행 FOR UPDATE -> 신청 행 FOR UPDATE -> 신청 행을 다시 읽어 PENDING 확인.
    // 반드시 바깥 트랜잭션 안에서 부른다(잠금 쿼리가 MANDATORY).
    private LockedRequest lockPending(Long id, Long userId) {
        if (userRepository.lockById(userId).isEmpty()) {
            throw notFound(); // 읽은 뒤 잠그기 전에 탈퇴함(신청도 CASCADE로 지워짐)
        }
        if (permitRequestRepository.lockById(id).isEmpty()) {
            throw notFound();
        }
        // 잠근 뒤에 읽어야 다른 관리자가 방금 커밋한 최신 상태를 본다
        PermitRequest request = permitRequestRepository.findById(id).orElseThrow(AdminPermitService::notFound);
        if (request.getStatus() != PermitStatus.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 신청입니다.");
        }
        User user = userRepository.findById(userId).orElseThrow(AdminPermitService::notFound);
        return new LockedRequest(request, user);
    }

    // 관리자 본인의 신청은 본인이 심사할 수 없다(자기 허가서를 스스로 승인하는 것 방지) -> 403. 잠금 전에 판단한다.
    private void rejectSelfReview(Long applicantId, LoginUser loginUser) {
        if (applicantId.equals(loginUser.id())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "본인의 허가서 신청은 직접 심사할 수 없습니다.");
        }
    }

    // 사진 key를 모아 두고 "지울 파일 대기 표"에 적는다(같은 트랜잭션이라 결정과 함께 커밋/롤백). 사진이 없으면(응답 유실 등) 건너뛴다.
    private void collectAndEnqueuePhoto(PermitRequest request, List<String> photoKeys) {
        String key = request.getPhotoKey();
        if (key != null) {
            photoDeleteQueue.enqueue(List.of(key));
            photoKeys.add(key);
        }
    }

    // 처리된 신청을 응답으로 만든다. 사진 칸이 이미 비워진 뒤라 hasPhoto=false.
    private AdminPermitRequestResponse toResponse(PermitRequest request, User user, String schoolName) {
        String detectedName = request.getDetectedSchoolId() == null ? null
                : schoolRepository.findById(request.getDetectedSchoolId()).map(School::getName).orElse(null);
        return AdminPermitRequestResponse.of(request, user.getNickname(), schoolName, detectedName);
    }

    // 깨진 제약 이름으로 오류를 나눈다. 같은 허가서 번호가 이미 승인됨 409. 그 밖은 그대로 던져 500(예상 못 한 위반을 숨기지 않는다).
    private RuntimeException translate(DataIntegrityViolationException e) {
        String constraint = ConstraintViolations.violatedConstraint(e).orElse("");
        if (UNIQUE_PERMIT_HASH.equals(constraint)) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "이미 승인된 허가서 번호입니다.");
        }
        if (FK_DECIDER.equals(constraint)) {
            return ApiErrors.loginRequired(); // 처리하는 사이 이 관리자 계정이 탈퇴함
        }
        return e;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "허가서 인증 신청을 찾을 수 없습니다.");
    }

    // 잠금을 잡은 신청과 그 신청자
    private record LockedRequest(PermitRequest request, User user) {
    }
}
