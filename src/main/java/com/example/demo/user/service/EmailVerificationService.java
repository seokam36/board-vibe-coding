package com.example.demo.user.service;

import java.security.SecureRandom;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.example.demo.auth.service.AccessGuard;
import com.example.demo.school.domain.SchoolEmailDomain;
import com.example.demo.school.repository.SchoolEmailDomainRepository;
import com.example.demo.user.domain.User;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.user.service.EmailVerificationCodeStore.ReserveResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 학교 메일 인증 코드 발송(S10-b). 확인은 S10-c.
// 트랜잭션을 걸지 않는다: DB 조회는 각자 짧게 끝내고, 느릴 수 있는 메일 발송 동안 DB 연결(풀 3개)을 붙잡지 않기 위함.
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
    //  5) 재발송 60초·하루 10회 429 - Redis를 쓰는 검사라 맨 뒤. 통과와 동시에 코드를 저장한다(원자적)
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
            String message = result == ReserveResult.COOLDOWN
                    ? "인증 메일은 잠시 후에 다시 요청할 수 있습니다."
                    : "오늘 인증 메일 요청 횟수를 모두 사용했습니다.";
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }

        try {
            mailSender.sendCode(email, code);
        } catch (RuntimeException e) {
            // 발송 실패: 저장한 코드·쿨다운·횟수를 되돌려 바로 재시도할 수 있게 한다. 코드·메일은 로그에 남기지 않는다.
            // (S10-e 메모: SMTP 예외 메시지에 받는 주소가 섞일 수 있으니 실제 발송기를 붙일 때 로그 내용을 확인할 것)
            log.warn("인증 메일 발송 실패 userId={}", userId, e);
            codeStore.rollback(userId, code);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "인증 메일을 보내지 못했습니다. 잠시 후 다시 시도하세요.");
        }
    }

    // 6자리 숫자 코드. SecureRandom으로 만들고 앞자리 0도 허용한다("000123").
    private String generateCode() {
        return String.format("%0" + CODE_DIGITS + "d", secureRandom.nextInt(1_000_000));
    }
}
