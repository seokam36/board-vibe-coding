package com.example.demo.user.service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.service.AccessGuard;
import com.example.demo.common.ApiErrors;
import com.example.demo.common.ConstraintViolations;
import com.example.demo.school.domain.SchoolEmailDomain;
import com.example.demo.school.repository.SchoolEmailDomainRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.dto.UserResponse;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.user.service.EmailVerificationCodeStore.ConfirmResult;
import com.example.demo.user.service.EmailVerificationCodeStore.ReserveResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 학교 메일 인증 코드 발송(S10-b)과 확인(S10-c).
// 트랜잭션을 걸지 않는다: DB 조회는 각자 짧게 끝내고, 느릴 수 있는 메일 발송 동안 DB 연결(풀 3개)을 붙잡지 않기 위함.
// MailConfig(메일 설정 시작 검사)가 먼저 만들어지도록 고정: MAIL_MODE 오타 등을 Spring의 "빈 없음" 오류보다 먼저 우리 메시지로 알린다
@DependsOn("mailConfig")
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final int CODE_DIGITS = 6;

    private final AccessGuard accessGuard;
    private final SchoolEmailDomainRepository domainRepository;
    private final UserRepository userRepository;
    private final EmailVerificationCodeStore codeStore;
    private final VerificationMailSender mailSender;
    private final SecureRandom secureRandom = new SecureRandom();

    // 검사 순서와 이유 (본문 형식 400은 컨트롤러의 @Valid가 먼저 한다):
    //  1) 회원 확인 401  - 누구인지부터 확정
    //  2) 메일 정규화·도메인 검사 400 - 목록에 없는 도메인은 이후 어떤 검사도 할 필요가 없고, 싸게 걸러진다
    //  3) 이미 인증된 회원 409 - 인증됐다면 메일을 또 보낼 이유가 없다
    //  4) 다른 회원이 쓰는 메일 409 - 발송 횟수를 쓰기 전에 거른다(어차피 인증을 끝낼 수 없는 메일이므로)
    //  5) 재발송 60초·회원 하루 10회·같은 메일 주소 하루 5회 429 - Redis를 쓰는 검사라 맨 뒤. 통과와 동시에 코드를 저장한다(원자적)
    //  6) 발송 - 실패하면 저장·제한을 되돌려 바로 다시 시도할 수 있게 한다
    public void sendCode(Long userId, String rawEmail) {
        User user = accessGuard.requireUser(userId);

        String email = SchoolEmailHash.normalize(rawEmail);
        String domain = email.substring(email.lastIndexOf('@') + 1);
        SchoolEmailDomain schoolDomain = domainRepository.findByDomain(domain)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 학교 메일입니다."));

        if (user.getVerifiedSchool() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 학교 인증을 마쳤습니다.");
        }
        if (userRepository.existsBySchoolEmailHash(SchoolEmailHash.of(email))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 계정이 인증에 사용한 메일입니다.");
        }

        String code = generateCode();
        ReserveResult result = codeStore.reserve(userId, code, email, schoolDomain.getSchool().getId());
        if (result != ReserveResult.OK) {
            String message = switch (result) {
                case COOLDOWN -> "인증 메일은 잠시 후에 다시 요청할 수 있습니다.";
                case TARGET_LIMIT, PAIR_LIMIT -> "이 메일 주소로는 오늘 더 보낼 수 없습니다.";
                default -> "오늘 인증 메일 요청 횟수를 모두 사용했습니다.";
            };
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }

        try {
            mailSender.sendCode(email, code);
        } catch (RuntimeException e) {
            // 발송 실패: 저장한 코드·쿨다운·횟수를 되돌려 바로 재시도할 수 있게 한다. 코드·메일은 로그에 남기지 않는다.
            // (S10-e 메모: SMTP 예외 메시지에 받는 주소가 섞일 수 있으니 실제 발송기를 붙일 때 로그 내용을 확인할 것)
            log.warn("인증 메일 발송 실패 userId={}", userId, e);
            codeStore.rollback(userId, code, email);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도하세요.");
        }
    }

    // 코드 확인. 검사 순서:
    //  1) 회원 확인 401  2) 이미 인증됨 409(코드를 소모하지 않도록 Redis 확인보다 먼저)
    //  3) Redis 확인 (불일치 400 / 코드가 더 이상 없음[없음·만료·폐기] 410. 화면이 상태코드로만 문구를 정하므로 나눈다)  4) 조건부 UPDATE(아직 미인증일 때만)  5) 다시 읽어 응답
    // 주의: 3)에서 코드는 이미 지워졌으므로 4)가 409로 실패하면 사용자는 코드를 다시 받아야 한다(그대로 둔다).
    public UserResponse confirmCode(Long userId, String code) {
        User user = accessGuard.requireUser(userId);
        if (user.getVerifiedSchool() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 학교 인증을 마쳤습니다.");
        }

        ConfirmResult result = codeStore.confirm(userId, code);
        switch (result.status()) {
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.GONE, "인증 코드가 없거나 만료되었습니다.");
            case WRONG -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "인증 코드가 올바르지 않습니다.");
            case DISCARDED -> throw new ResponseStatusException(HttpStatus.GONE, "틀린 횟수를 넘어 코드가 폐기되었습니다. 다시 받아 주세요.");
            case MATCHED -> { }
        }

        // 메일 원문은 저장하지 않고 해시만 DB에 남긴다
        String emailHash = SchoolEmailHash.of(result.email());
        int updated;
        try {
            // 자기 트랜잭션으로 실행되므로 UNIQUE·FK 위반은 여기서 예외로 나온다
            updated = userRepository.markVerified(userId, result.schoolId(), emailHash, Instant.now().truncatedTo(ChronoUnit.MICROS)); // User.onCreate와 같은 마이크로초
        } catch (DataIntegrityViolationException e) {
            String constraint = ConstraintViolations.violatedConstraint(e).orElse("");
            if (constraint.equals("uk_users_school_email_hash")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 계정이 인증에 사용한 메일입니다.");
            }
            if (constraint.equals("fk_users_verified_school")) {
                // 코드를 받은 뒤 학교가 삭제된 극단적인 경우
                throw new ResponseStatusException(HttpStatus.CONFLICT, "인증하려던 학교가 더 이상 없습니다.");
            }
            throw e; // 다른 제약 위반은 숨기지 않는다
        }
        if (updated == 0) {
            // 거의 안 타는 방어용: 같은 코드는 Redis에서 한 요청만 통과하므로 보통 여기까지 못 온다.
            // 그 사이 다른 요청이 인증을 끝냈거나 회원이 탈퇴했다. 회원이 있으면 409, 없으면 401
            if (userRepository.existsById(userId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 학교 인증을 마쳤습니다.");
            }
            throw ApiErrors.loginRequired();
        }

        // EntityGraph가 걸린 findById라 트랜잭션 밖에서도 verifiedSchool을 바로 응답으로 바꿀 수 있다
        return UserResponse.from(accessGuard.requireUser(userId));
    }

    // 6자리 숫자 코드. SecureRandom으로 만들고 앞자리 0도 허용한다("000123").
    private String generateCode() {
        return String.format("%0" + CODE_DIGITS + "d", secureRandom.nextInt(1_000_000));
    }
}
