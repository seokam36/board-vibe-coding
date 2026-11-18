package com.example.demo.permit.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.dto.LoginUser;
import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.permit.domain.PermitRequest;
import com.example.demo.permit.domain.PermitStatus;
import com.example.demo.permit.dto.PermitStatusResponse;
import com.example.demo.permit.repository.PermitRequestRepository;
import com.example.demo.photo.repository.PhotoStorage;
import com.example.demo.photo.repository.PhotoStorageException;
import com.example.demo.photo.service.ImageProcessor;
import com.example.demo.photo.service.InvalidImageException;
import com.example.demo.photo.service.PhotoDeleteQueue;
import com.example.demo.photo.service.ProcessedImage;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 표준입학허가서 인증 "신청" (S20-a): 신청하기·내 상태 보기·철회하기. 승인·거절은 S20-b(관리자)가, 신청 직후 자동 심사(S20-d2)는 PermitAutoReview가 맡는다.
// 사진은 결과가 나올 때까지만 보관한다(대기 중에만). 허가서 사진은 개인정보라 로그에 파일 이름·사진 key·내용을 남기지 않고 회원 id와 결과 종류만 남긴다.
//
// 신청 흐름(트랜잭션은 맨 마지막 짧은 구간에만 건다. 이미지 처리와 창고 저장은 오래 걸려서 DB 연결을 쥐고 하지 않는다):
//   1) 입력 400(동의·파일) -> 싼 확인(이미 승인 409, 이미 대기 409) -> 하루 3회 429
//   2) 이미지 처리(JPEG/PNG 판별·축소·EXIF 제거): 동시에 1장까지만, 5초 안에 자리가 안 나면 503
//   3) 창고에 저장 (key는 허가서 전용 접두사 permits/)
//   4) 짧은 트랜잭션: 회원 행 FOR KEY SHARE 잠금(탈퇴와 줄 세우기) -> 이미 승인 다시 확인 -> 신청 행 INSERT
//   5) (4가 커밋된 뒤, 트랜잭션 밖) 자동 심사: 판독기로 글자 읽기 -> 판정 -> 모두 통과면 자동 승인, 아니면 PENDING 유지(PermitAutoReview)
//   3)~4)가 실패하면 저장한 사진을 지우고, 지우기도 실패하면 그 키만 "지울 파일 대기 표"에 적는다.
// 중고 사진과 같은 안전장치: 저장 전에 key를 지울 파일 대기 표에 먼저 적고, 4)의 트랜잭션에서 INSERT와 함께 뗀다
// (청소부는 10분 안 된 줄을 건드리지 않고, 뗄 때 이미 집었으면 롤백 503). 그래서 저장 직후 서버가 죽어도 고아 파일이 청소된다.
// 서버 탓 실패(503·500·진 409 등)는 하루 3회 카운트를 되돌린다. 깨진 사진 400만 센다.
@Slf4j
@Service
@RequiredArgsConstructor
public class PermitVerificationService {

    // DB 제약 이름 (V14). 깨진 제약 이름으로 오류 종류를 구분한다.
    static final String UNIQUE_PENDING = "uk_permit_requests_user_pending";
    static final String FK_USER = "fk_permit_requests_user";

    private final PermitRequestRepository permitRequestRepository;
    private final UserRepository userRepository;
    private final AccessGuard accessGuard;
    private final ImageProcessor imageProcessor;
    private final PhotoStorage photoStorage;
    private final PhotoDeleteQueue photoDeleteQueue;
    private final PermitRateLimiter rateLimiter;
    private final PlatformTransactionManager txManager;
    private final PermitAutoReview autoReview;

    // 이미지 처리 동시 1장 제한: 중고 사진(동시 2장)과 별개로 이 기능만의 자리. 허가서는 드문 요청이고 메모리를 아끼려고 1장으로 둔다.
    // 5초 기다려도 자리가 안 나면 503. (테스트가 필드 이름으로 접근해 permit을 쥐고 대기 시간을 줄여 본다)
    final Semaphore processPermits = new Semaphore(1);
    long processWaitMillis = 5_000;

    // 신청하기. 검사 순서: 동의·파일 400 -> 회원 없음 401 -> 이미 승인 409 -> 이미 대기 409 -> 하루 3회 429
    // -> 사진 검사 400(형식·깨짐·크기) / 처리 자리 없음 503 -> (잠금 뒤) 회원 없음 401 / 이미 승인 409 / 동시에 먼저 신청됨 409
    public PermitStatusResponse submit(LoginUser loginUser, MultipartFile file, Boolean consent) {
        Long userId = loginUser.id();
        // 1) 입력 400. 동의 없이는 사진을 받지 않는다.
        if (!Boolean.TRUE.equals(consent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "사진 수집·이용에 동의해야 신청할 수 있습니다.");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허가서 사진 파일이 필요합니다.");
        }

        // 싼 확인(읽기만): 무거운 처리 전에 거절한다. 동시 요청에서는 틀릴 수 있어 4)의 잠금 안 검사·DB 부분 UNIQUE가 최종이다.
        TransactionTemplate readOnly = new TransactionTemplate(txManager);
        readOnly.setReadOnly(true);
        readOnly.executeWithoutResult(status -> {
            User user = accessGuard.requireUser(loginUser);
            if (user.getVerifiedSchool() != null) {
                throw alreadyApproved();
            }
            if (permitRequestRepository.existsByUserIdAndStatus(userId, PermitStatus.PENDING)) {
                throw alreadyPending();
            }
        });

        // 하루 3회: 싼 확인을 통과한 시도부터 센다(이미 신청 중·승인으로 거절된 요청은 세지 않는다).
        // 사진이 깨져 400이 난 시도는 센다(남용 방지). 서버 탓 실패(503·500·동시 신청에서 진 409 등)는 아래에서 카운트를 되돌린다.
        rateLimiter.countOrReject(userId);
        StoredRequest stored;
        try {
            stored = processAndStore(userId, file);
        } catch (RuntimeException | Error e) {
            boolean badPhoto = e instanceof ResponseStatusException r && r.getStatusCode() == HttpStatus.BAD_REQUEST;
            if (!badPhoto) {
                rateLimiter.refund(userId);
            }
            throw e;
        }
        // 5) 자동 심사(S20-d2): 신청 행은 이미 커밋됐고 이미지 처리 자리(processPermits)도 돌려준 뒤다. 위 try/catch "밖"에 둔 이유:
        // 여기서 나는 문제가 위 catch로 가면 이미 커밋된 신청의 사진을 지우고 하루 횟수를 되돌려 버린다. 예외(RuntimeException)가 나도 신청은 PENDING으로 남고 201이다(메모리 부족 같은 Error는 500이지만 신청은 PENDING으로 남는다).
        return runAutoReview(userId, stored);
    }

    // 5) 글자 읽기 -> 판정 -> (모두 통과면) 자동 승인. 자동 승인되면 APPROVED 응답, 아니면 INSERT 때의 PENDING 응답을 그대로 돌려준다.
    // 판독기·판정·승인 어디서 예외가 나도 삼킨다(5xx로 바꾸지 않는다). 로그에는 예외 클래스 이름만(메시지에 읽은 글자가 섞일 수 있다).
    private PermitStatusResponse runAutoReview(Long userId, StoredRequest stored) {
        try {
            return autoReview.review(userId, stored.requestId(), stored.imageBytes(), stored.contentType())
                    .orElse(stored.response());
        } catch (RuntimeException e) {
            log.warn("허가서 자동 심사 실패, 대기 유지: userId={}, requestId={}, error={}", userId, stored.requestId(),
                    e.getClass().getSimpleName());
            return stored.response();
        }
    }

    // 신청 행을 넣고 난 결과: INSERT 시점의 응답(PENDING), 신청 id, 자동 심사에 넘길 처리된 사진 바이트(창고에서 다시 읽지 않으려고 들고 간다)
    private record StoredRequest(PermitStatusResponse response, Long requestId, byte[] imageBytes, String contentType) {
    }

    // 2)~4) 이미지 처리 -> 창고 저장 -> DB 기록
    private StoredRequest processAndStore(Long userId, MultipartFile file) {
        // 2) 이미지 처리 (트랜잭션 밖, 동시 1장)
        ProcessedImage image = processLimited(file);

        // 3)~4) 창고 저장 + DB 기록. 어디서 실패하든 저장한 사진을 치운다.
        String ext = "JPEG".equals(image.main().format()) ? "jpg" : "png";
        String key = "permits/" + userId + "/" + UUID.randomUUID() + "." + ext; // 키는 UUID 기반이라 추측·충돌이 없다
        List<String> keys = List.of(key);
        // 창고에 넣기 "전에" 새 key를 지울 파일 대기 표에 먼저 적는다(중고 사진과 같은 방식, 짧은 별도 트랜잭션으로 커밋).
        // 저장 직후 서버가 죽어도 이름이 표에 남아 청소부가 고아 파일을 지운다. 성공하면 4)에서 같이 뗀다.
        // 청소부는 10분 안 된 줄은 건드리지 않고, 4)에서 쪽지를 뗄 때 청소부가 이미 집었으면 롤백(503)하므로
        // "쓰는 중인 허가서 사진이 지워지는" 일이 없다(중고 사진과 같은 보호).
        photoDeleteQueue.enqueue(keys);
        try {
            // 허가서는 본 사진 한 장만 보관한다(썸네일은 쓰지 않는다)
            try {
                photoStorage.put(key, image.main().data(), image.main().contentType());
            } catch (PhotoStorageException e) {
                // 창고 실패: 예외 이름과 userId만 남기고(메시지·key·스택은 남기지 않는다) 원인 없이 500으로 바꾼다.
                // 아래 바깥 catch가 올린 파일 치우기를 하고, 위의 catch가 하루 횟수를 되돌린다.
                log.warn("허가서 사진 저장 실패: userId={}, error={}", userId, e.getClass().getSimpleName());
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR);
            }
            PermitRequest saved = new TransactionTemplate(txManager)
                    .execute(status -> insertLocked(userId, key, image.main().contentType()));
            log.info("허가서 신청 접수: userId={}", userId); // 파일 이름·key는 남기지 않는다
            return new StoredRequest(PermitStatusResponse.from(saved), saved.getId(), image.main().data(),
                    image.main().contentType());
        } catch (RuntimeException | Error e) {
            // 파일을 지우고 지운 키는 표에서 뺀다. 못 지우면 표에 남아 청소부가 처리한다(예외는 던지지 않고 원래 예외 e가 우선)
            photoDeleteQueue.deleteFilesAndDequeue(keys);
            if (e instanceof DataIntegrityViolationException dive) {
                throw translate(dive);
            }
            throw e;
        }
    }

    // 내 허가서 인증 상태. verified_school_id가 있으면 신청 행이 없어도(예전 메일 인증 회원·운영자 SQL 등) APPROVED.
    // 없으면 가장 최근 신청 행의 상태, 신청이 없으면 NONE.
    @Transactional(readOnly = true)
    public PermitStatusResponse getStatus(LoginUser loginUser) {
        User user = accessGuard.requireUser(loginUser);
        if (user.getVerifiedSchool() != null) {
            return permitRequestRepository.findFirstByUserIdAndStatusOrderByIdDesc(user.getId(), PermitStatus.APPROVED)
                    .map(PermitStatusResponse::approvedFrom)
                    .orElseGet(PermitStatusResponse::approvedWithoutRequest);
        }
        return permitRequestRepository.findFirstByUserIdOrderByIdDesc(user.getId())
                .map(PermitStatusResponse::from)
                .orElseGet(PermitStatusResponse::none);
    }

    // 신청 철회 / 인증 해제. 한 트랜잭션에서: 회원 행 FOR UPDATE(탈퇴와 같은 잠금 순서) -> 이 회원의 신청 행 삭제(취소된 REVOKED 행은 남김)
    // -> 학교 인증(verified_school_id·verified_at·school_email_hash)이 있으면 NULL로 비움(이후 글쓰기는 403).
    // 신청도 학교 인증도 없으면 404. 예전 메일 인증 회원(행 없이 학교만 있음)도 같은 처리다.
    // 사진 파일은 커밋 뒤에 지운다. 지울 키는 같은 트랜잭션에서 대기 표에 적어 두는데, 이 키의 행이 같은 트랜잭션에서 지워지므로
    // (롤백되면 표 적기도 같이 롤백) "쓰는 중인 사진이 지워지는" 일이 없고, 커밋 뒤 삭제가 실패하거나 서버가 죽어도 청소부가 지운다.
    public void cancel(LoginUser loginUser) {
        Long userId = loginUser.id();
        List<String> photoKeys = new ArrayList<>();
        new TransactionTemplate(txManager).executeWithoutResult(status -> {
            if (userRepository.lockById(userId).isEmpty()) {
                throw ApiErrors.loginRequired();
            }
            User user = userRepository.findById(userId).orElseThrow(ApiErrors::loginRequired);
            boolean verified = user.getVerifiedSchool() != null;
            List<String> keys = permitRequestRepository.findPhotoKeysByUserId(userId);
            // 취소된(REVOKED) 행은 지우지 않는다(S21-b). 그것만 있고 인증도 없으면 deleted가 0이라 아래에서 404가 된다
            int deleted = permitRequestRepository.deleteByUserIdExceptStatus(userId, PermitStatus.REVOKED);
            if (deleted == 0 && !verified) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "철회할 허가서 인증 신청이나 인증이 없습니다.");
            }
            photoDeleteQueue.enqueue(keys);
            if (verified && userRepository.clearVerification(userId) == 0) {
                throw ApiErrors.loginRequired();
            }
            photoKeys.addAll(keys);
        });
        // 커밋 뒤: 파일을 지우고 지운 키를 표에서 뺀다(실패해도 예외 없이 표에 남아 청소부가 처리)
        photoDeleteQueue.deleteFilesAndDequeue(photoKeys);
        log.info("허가서 인증 철회: userId={}", userId);
    }

    // 4) 짧은 트랜잭션: 회원 행을 FOR KEY SHARE로 잡고(탈퇴의 FOR UPDATE와 줄을 세운다) 다시 확인한 뒤 신청 행을 넣는다.
    // 탈퇴가 먼저면 여기서 회원이 없어 401, 이 INSERT가 먼저면 탈퇴가 끝나기를 기다렸다가 탈퇴가 이 행과 사진을 함께 정리한다.
    // saveAndFlush: 부분 UNIQUE 위반을 이 트랜잭션 안에서 바로 드러내 바깥 catch가 409로 바꾸게 한다.
    private PermitRequest insertLocked(Long userId, String key, String contentType) {
        if (userRepository.lockForKeyShare(List.of(userId)).isEmpty()) {
            throw ApiErrors.loginRequired();
        }
        User user = userRepository.findById(userId).orElseThrow(ApiErrors::loginRequired);
        if (user.getVerifiedSchool() != null) {
            throw alreadyApproved(); // 처리하는 사이에 운영자 SQL 등 다른 경로로 승인됨
        }
        // 쪽지 떼기와 INSERT는 같은 트랜잭션: 올리는 사이 청소부가 쪽지를 집었거나 지웠다면 파일이 곧 사라지니 503으로 롤백한다
        if (!photoDeleteQueue.dequeueIfUntouched(List.of(key))) {
            log.warn("허가서 신청 중 청소부와 겹쳐 503 처리: userId={}", userId); // key는 남기지 않는다
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "올리는 사이 정리 작업과 겹쳤습니다. 다시 시도해 주세요.");
        }
        return permitRequestRepository.saveAndFlush(new PermitRequest(userId, key, contentType));
    }

    // 깨진 제약 이름으로 오류를 나눈다. 대기 중 신청 중복 409, 회원 없음(탈퇴) 401. 그 밖은 그대로 던져 500(예상 못 한 위반을 숨기지 않는다).
    private RuntimeException translate(DataIntegrityViolationException e) {
        String constraint = ConstraintViolations.violatedConstraint(e).orElse("");
        if (UNIQUE_PENDING.equals(constraint)) {
            return alreadyPending();
        }
        if (FK_USER.equals(constraint)) {
            return ApiErrors.loginRequired();
        }
        return e;
    }

    // 처리기를 부르되 동시에 1장까지만. 자리가 날 때까지 최대 processWaitMillis 기다리고, 안 나면 503.
    // 파일 바이트는 자리를 얻은 뒤에 읽는다(자리를 기다리는 요청마다 메모리를 쥐고 있지 않게).
    private ProcessedImage processLimited(MultipartFile file) {
        boolean acquired;
        try {
            acquired = processPermits.tryAcquire(processWaitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 중단 신호를 되살려 둔다
            throw busy();
        }
        if (!acquired) {
            throw busy();
        }
        try {
            return imageProcessor.process(readBytes(file));
        } catch (InvalidImageException e) {
            // 받을 수 없는 사진은 모두 400 (중고 사진 올리기와 같다. 415는 쓰지 않는다)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, switch (e.getReason()) {
                case UNSUPPORTED_FORMAT -> "JPEG·PNG 사진만 올릴 수 있습니다.";
                case TOO_LARGE -> "사진 크기(픽셀 수)가 너무 큽니다.";
                case CORRUPTED -> "깨진 사진 파일입니다.";
            }, e);
        } finally {
            processPermits.release(); // 성공·실패 상관없이 자리를 돌려준다
        }
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            // 손님이 잘못 보낸 게 아니라 서버가 받아 둔 임시 파일을 못 읽는 것이므로 500
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "사진 파일을 읽을 수 없습니다.", e);
        }
    }

    private ResponseStatusException busy() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "사진 처리가 밀려 있습니다. 잠시 뒤 다시 시도해 주세요.");
    }

    private ResponseStatusException alreadyApproved() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이미 인증을 마친 회원입니다.");
    }

    private ResponseStatusException alreadyPending() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "이미 심사 중인 신청이 있습니다.");
    }
}
