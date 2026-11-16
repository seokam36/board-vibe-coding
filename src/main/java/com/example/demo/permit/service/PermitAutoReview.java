package com.example.demo.permit.service;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.demo.permit.domain.PermitMachineResult;
import com.example.demo.permit.domain.PermitRequest;
import com.example.demo.permit.domain.PermitStatus;
import com.example.demo.permit.dto.PermitStatusResponse;
import com.example.demo.permit.repository.PermitRequestRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// 신청 직후의 "자동 심사" (S20-d2): 사진 글자를 읽고(판독기) -> 4가지 규칙으로 판정하고(PermitJudge) -> 결과를 처리한다.
//   모두 통과(ALL_PASSED)이고 번호가 처음이면 자동 승인(AdminPermitService.autoApprove).
//   그 밖(못 읽음·규칙 불통과·이미 승인된 번호·승인 순간 경쟁에서 짐)은 신청을 PENDING 그대로 두고 판정 결과만 적는다. 자동 반려는 없다.
// 이 클래스에는 @Transactional이 없다: 판독기는 느릴 수 있어 DB 연결을 쥐고 부르면 안 되고,
// PermitJudge(읽기 전용 트랜잭션을 씀)도 쓰기 트랜잭션 안에서 부르면 예외 때 바깥 트랜잭션이 롤백 전용이 돼 커밋이 500이 되기 때문이다.
// 읽은 글자·허가서 번호·해시는 개인정보라 로그에 절대 남기지 않는다(회원 id·신청 id·결과 코드·예외 클래스 이름만).
@Slf4j
@Component
@RequiredArgsConstructor
public class PermitAutoReview {

    private final PermitTextReader reader;
    private final PermitJudge judge;
    private final AdminPermitService adminPermitService;
    private final PermitRequestRepository permitRequestRepository;

    // 자동 승인되면 승인된 상태 응답을, 아니면 빈 값(호출한 쪽이 INSERT 시점의 PENDING 응답을 그대로 쓴다)을 돌려준다.
    public Optional<PermitStatusResponse> review(Long userId, Long requestId, byte[] image, String contentType) {
        PermitJudgement judgement = readAndJudge(userId, requestId, image, contentType);

        if (judgement.result() != PermitMachineResult.ALL_PASSED) {
            record(userId, requestId, judgement.result(), judgement.detectedSchoolId());
            return Optional.empty();
        }

        AdminPermitService.AutoApproval approval = adminPermitService.autoApprove(requestId, userId, judgement);
        switch (approval.outcome()) {
            case APPROVED -> {
                log.info("허가서 자동 승인: userId={}, requestId={}, result={}", userId, requestId, PermitMachineResult.ALL_PASSED);
                PermitRequest approved = approval.request();
                return Optional.of(PermitStatusResponse.from(approved));
            }
            case NUMBER_DUPLICATE -> record(userId, requestId, PermitMachineResult.NUMBER_DUPLICATE, judgement.detectedSchoolId());
            // 신청이 사라졌거나(철회·탈퇴) 이미 처리됐거나 이미 인증된 회원: 승인하지 않고 판정 결과만 적는다(행이 없거나 PENDING이 아니면 아무 일도 안 일어난다)
            case NOT_APPLIED -> record(userId, requestId, PermitMachineResult.ALL_PASSED, judgement.detectedSchoolId());
        }
        return Optional.empty();
    }

    // 글자 읽기 -> 판정. 못 읽거나(빈 값·공백·예외) 하면 판정 규칙을 부르지 않고 UNREADABLE로 한다(빈 글자를 judge에 주면 NO_TITLE이 나오기 때문).
    private PermitJudgement readAndJudge(Long userId, Long requestId, byte[] image, String contentType) {
        Optional<String> text;
        try {
            text = reader.read(image, contentType);
        } catch (RuntimeException e) {
            // 메시지·스택에 읽은 글자나 경로가 섞일 수 있어 예외 클래스 이름만 남긴다
            log.warn("허가서 판독기 오류: userId={}, requestId={}, error={}", userId, requestId, e.getClass().getSimpleName());
            text = Optional.empty();
        }
        if (text == null || text.isEmpty() || text.get().isBlank()) {
            return new PermitJudgement(PermitMachineResult.UNREADABLE, null, null);
        }
        return judge.judge(text.get());
    }

    // 판정 결과만 신청 행에 적는다(PENDING일 때만. 0건이면 그 사이 철회·처리된 것이라 무시).
    private void record(Long userId, Long requestId, PermitMachineResult result, Long detectedSchoolId) {
        int updated = permitRequestRepository.recordMachineResult(requestId, result.name(), detectedSchoolId,
                PermitStatus.PENDING);
        log.info("허가서 자동 심사 결과(대기 유지): userId={}, requestId={}, result={}, recorded={}", userId, requestId, result,
                updated > 0);
    }
}
