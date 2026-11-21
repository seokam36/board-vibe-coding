package com.example.demo.push.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.demo.push.repository.DeviceTokenRepository;
import com.example.demo.push.repository.VisaPlanNotificationRepository;
import com.example.demo.user.repository.UserRepository;
import com.example.demo.visa.domain.VisaPlanStage;
import com.example.demo.visa.repository.VisaPlanRepository;

import lombok.RequiredArgsConstructor;

// 비자 일정 푸시 알림, 회원 한 명 처리 (S24-c). 누구에게 보낼지는 VisaPushJob이 고르고, 여기서는 "그 회원의 기기 전부에 보내고 기록"만 한다.
// 일부러 메서드 전체에 @Transactional을 걸지 않았다: 구글을 부르는 동안 DB 연결을 잡고 있으면
// 연결 풀(운영 3개)이 금방 마르기 때문이다. 순서는 (1) 짧게 토큰 읽기·재확인 (2) 트랜잭션 밖에서 발송 (3) 짧은 트랜잭션 하나로 기록·토큰 삭제.
// (3)은 맨 앞에서 회원 행을 FOR KEY SHARE로 먼저 잡는다(쪽지 S14-a와 같은 규칙): 탈퇴·토큰 등록은 "회원 행 먼저"라서
// 우리도 회원 행부터 잡아야 잠금 순서가 같아 교착이 안 난다. 로그·예외에 토큰 값을 남기지 않는다.
@Service
@RequiredArgsConstructor
public class VisaPushService {

    // 알림 문구는 세 단계 모두 같은 일반 문구다(잠금 화면에 보이므로 날짜·단계를 넣지 않는다)
    public static final String TITLE = "비자 일정 알림";
    public static final String BODY = "비자 일정을 확인할 때가 됐어요. 앱에서 확인해 주세요.";

    // 회원 한 명을 처리한 결과
    public enum MemberResult {
        SENT,     // 한 기기 이상 구글이 받아 줬다 -> 기록을 남겼다
        INVALID,  // 한 번도 못 받았지만 "못 쓰는 토큰"이라는 구글 답은 받았다(그 토큰은 지웠다) -> 기록 없음
        REJECTED, // 받은 건 없고, 구글이 4xx로 거절만 했다(토큰·요청 문제, 구글은 살아 있음) -> 기록 없음, 다음 회차에 다시
        FAILED,   // 첫 FAILED에서 멈춘 회원(구글 무응답: 연결 문제·시간 초과·5xx·429) -> 기록 없음, 다음 회차에 다시
        SKIPPED   // 그 사이 대상이 아니게 됐다(토큰 없음·인증 취소·만료일 변경·탈퇴) -> 할 일 없음
    }

    private final DeviceTokenRepository deviceTokenRepository;
    private final VisaPlanNotificationRepository notificationRepository;
    private final VisaPlanRepository visaPlanRepository;
    private final UserRepository userRepository;
    private final PushSender pushSender;
    private final TransactionTemplate transactionTemplate;

    public MemberResult notifyMember(Long userId, LocalDate expiryDate, VisaPlanStage stage) {
        // (1) 읽기: 짧은 읽기라 읽자마자 연결을 돌려준다. 회차 시작 때 뽑은 후보 정보가 그 뒤 바뀌었을 수 있어 다시 확인한다.
        if (visaPlanRepository.countStillTarget(userId, expiryDate) == 0) {
            return MemberResult.SKIPPED; // 인증이 취소됐거나 만료일이 바뀌었거나 탈퇴했다
        }
        List<String> tokens = deviceTokenRepository.findTokensByUserId(userId);
        if (tokens.isEmpty()) {
            return MemberResult.SKIPPED;
        }

        // (2) 발송: 트랜잭션(DB 연결) 밖에서 토큰마다 보낸다
        boolean anySent = false;
        boolean anyRejected = false;
        List<String> invalidTokens = new ArrayList<>();
        for (String token : tokens) {
            PushResult result = pushSender.send(token, TITLE, BODY);
            if (result == PushResult.SENT) {
                anySent = true;
            } else if (result == PushResult.TOKEN_INVALID) {
                invalidTokens.add(token);
            } else if (result == PushResult.REJECTED) {
                anyRejected = true;
            } else if (result == PushResult.FAILED) {
                // 구글이 답을 못 했다(연결·시간 초과·5xx). 같은 회원의 남은 토큰도 같을 가능성이 커서 건너뛴다
                // (구글이 죽은 날 토큰마다 10초씩 기다리는 것을 막는다). 기록·삭제 규칙은 그대로다.
                break;
            }
        }
        if (!anySent && invalidTokens.isEmpty()) {
            // 기록도 삭제도 없다. 토큰은 그대로 두고 다음 회차에 다시
            return anyRejected ? MemberResult.REJECTED : MemberResult.FAILED;
        }

        // (3) 기록·삭제: 짧은 쓰기 트랜잭션 하나. 못 쓰는 토큰은 아직 이 회원 것일 때만(user_id 조건) 지운다.
        boolean sent = anySent;
        MemberResult written = transactionTemplate.execute(status -> {
            // 가장 먼저 회원 행을 잡는다. 그 사이 탈퇴했으면 비어서 돌아온다(토큰도 CASCADE로 이미 사라짐) -> 조용히 끝
            if (userRepository.lockForKeyShare(List.of(userId)).isEmpty()) {
                return MemberResult.SKIPPED;
            }
            for (String token : invalidTokens) {
                deviceTokenRepository.deleteByUserIdAndToken(userId, token);
            }
            // 발송 중 일정이 삭제·변경됐으면 만료일 기록을 남기지 않는다(삭제 때 지운 기록이 다시 생기는 것을 막음).
            // 보낸 것은 사실이라 결과는 SENT 그대로 둔다. 이 확인과 INSERT 사이의 몇 밀리초 틈은 남아 있다(완전히 닫히진 않음).
            if (sent && visaPlanRepository.countStillTarget(userId, expiryDate) > 0) {
                // 이미 같은 기록이 있으면 조용히 넘어간다(ON CONFLICT DO NOTHING)
                notificationRepository.insertIfAbsent(userId, expiryDate, stage.name());
            }
            return sent ? MemberResult.SENT : MemberResult.INVALID;
        });
        return written;
    }
}
