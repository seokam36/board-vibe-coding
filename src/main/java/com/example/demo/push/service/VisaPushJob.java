package com.example.demo.push.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.demo.push.repository.VisaPlanNotificationRepository;
import com.example.demo.visa.domain.VisaMilestone;
import com.example.demo.visa.domain.VisaPlanStage;
import com.example.demo.visa.repository.VisaPlanRepository;
import com.example.demo.visa.repository.VisaPushTarget;

import lombok.extern.slf4j.Slf4j;

// 비자 일정 푸시 알림 주기 작업 (S24-c): 서버가 켜지고 5분 뒤 + 1시간마다, "알림 받을 사람"을 찾아 보내고 기록한다.
// 스케줄은 app.scheduling.enabled=false 이면 켜지지 않는다(테스트 설정). 테스트는 runOnce(now)를 직접 부른다.
// 운영 서버를 평소 꺼 두므로 cron(매일 몇 시) 대신 fixedDelay + initialDelay를 쓴다(PermitExpiryJob과 같은 틀).
// 규칙:
//  - 발송기가 꺼져 있으면(PUSH_MODE=off) 아무것도 안 한다(보내지도, 기록하지도 않음).
//  - 서울 시각이 발송 시간 창(기본 09시 이상 21시 미만) 밖이면 아무것도 안 한다. 다음 회차(1시간 뒤)에 다시 본다.
//  - 대상: 비자 일정 + 기기 토큰 1개 이상 + 유학생 인증이 지금 유효 + 지금 단계가 PREPARE·CHECK·URGENT + 그 (회원, 만료일, 단계) 기록 없음.
//    지난 단계는 안 보내고 "지금 단계"만 본다. 단계는 화면 띠와 같은 VisaPlanStage.of(남은 일수)를 쓴다.
//  - 연속으로 "토큰이 전부 FAILED(구글 무응답)"인 회원이 MAX_CONSECUTIVE_FAILED_MEMBERS명이면 그 회차를 멈춘다(구글이 죽었는데 회원마다 오래 기다리지 않게).
//    구글이 4xx로 거절한 회원(REJECTED)은 구글이 살아 있다는 뜻이라 세지 않는다(엉터리 토큰 회원이 뒤 회원을 굶기지 않게). 그 회원은 기록 없이 다음 회차에 다시.
// 대상 후보는 한 번에 전부 메모리에 읽는다: 후보 한 건이 숫자 두 개(회원 id, 날짜)뿐이고, 만료까지 120일 이내 + 토큰 있는 인증 회원만이라
// 지금 규모(수십~수천 명)에서 충분히 작다. 규모가 수만 명을 넘으면 id 기준으로 나눠 읽도록 바꾼다.
// 그때는 "이미 보낸 기록도 한 번에 읽어 Set으로 비교"하도록 같이 바꾼다(지금은 후보마다 existsBy... 한 번씩).
// 이 클래스는 트랜잭션을 잡지 않는다(회원 한 명 처리는 VisaPushService가 짧은 트랜잭션으로 한다).
// 로그에는 건수만 남긴다(토큰·회원 id·만료일 금지).
@Slf4j
@Component
public class VisaPushJob {

    // 연속으로 "토큰 전부 FAILED"인 회원이 이 수에 닿으면 그 회차를 멈춘다
    static final int MAX_CONSECUTIVE_FAILED_MEMBERS = 5;

    // 후보를 찾을 가장 먼 날짜까지의 일수 = 가장 먼 마일스톤(PREPARE, 120일). 숫자를 따로 쓰지 않고 마일스톤에서 구한다.
    private static final int MAX_DAYS_AHEAD = Arrays.stream(VisaMilestone.values())
            .mapToInt(VisaMilestone::getDaysBefore).max().orElseThrow();

    private final PushSender pushSender;
    private final VisaPlanRepository visaPlanRepository;
    private final VisaPlanNotificationRepository notificationRepository;
    private final VisaPushService visaPushService;
    private final Clock clock; // 서울 시계. VisaPlanService가 "오늘"을 구하는 것과 같은 시계
    private final int windowStartHour; // 이 시(서울) 정각부터 보낸다(포함)
    private final int windowEndHour;   // 이 시(서울) 정각부터는 안 보낸다(미포함)

    public VisaPushJob(PushSender pushSender, VisaPlanRepository visaPlanRepository,
            VisaPlanNotificationRepository notificationRepository, VisaPushService visaPushService, Clock clock,
            @Value("${app.push.visa.window-start-hour:9}") int windowStartHour,
            @Value("${app.push.visa.window-end-hour:21}") int windowEndHour) {
        // 잘못된 설정은 기동 때 바로 실패시킨다
        if (windowStartHour < 0 || windowEndHour > 24 || windowStartHour >= windowEndHour) {
            throw new IllegalArgumentException("app.push.visa.window-start-hour는 0 이상이고 window-end-hour(24 이하)보다 작아야 합니다: "
                    + windowStartHour + "~" + windowEndHour);
        }
        this.pushSender = pushSender;
        this.visaPlanRepository = visaPlanRepository;
        this.notificationRepository = notificationRepository;
        this.visaPushService = visaPushService;
        this.clock = clock;
        this.windowStartHour = windowStartHour;
        this.windowEndHour = windowEndHour;
    }

    // 스케줄러가 부르는 문. 예외가 나도 스케줄러가 멈추지 않게 여기서 잡아 로그만 남긴다.
    @Scheduled(fixedDelayString = "${app.push.visa.interval:PT1H}", initialDelayString = "${app.push.visa.initial-delay:PT5M}")
    void scheduledRun() {
        try {
            runOnce(Instant.now(clock));
        } catch (RuntimeException e) {
            // 예외 객체까지 남긴다(원인 추적용). 메시지에 토큰·만료일을 넣지 않는다.
            log.warn("비자 일정 푸시 알림 작업에 실패했습니다(다음 주기에 다시 합니다)", e);
        }
    }

    // 한 번 돌린다. 알림을 보낸(기록을 남긴) 회원 수를 돌려준다. 회원별 실패는 잡고 다음 회원으로 간다. 테스트에서도 직접 부른다.
    public int runOnce(Instant now) {
        if (!pushSender.enabled()) {
            return 0; // 꺼져 있으면 보내지도 기록하지도 않는다
        }
        ZonedDateTime seoulNow = now.atZone(clock.getZone());
        int hour = seoulNow.getHour();
        if (hour < windowStartHour || hour >= windowEndHour) {
            return 0; // 발송 시간 창 밖 (끝 시각 정각은 밖)
        }
        LocalDate today = seoulNow.toLocalDate();

        // 짧은 읽기 한 번으로 후보를 받는다(이 뒤로는 회원별로 따로 처리)
        List<VisaPushTarget> candidates = visaPlanRepository.findPushCandidates(today, today.plusDays(MAX_DAYS_AHEAD));

        int targets = 0;
        int sent = 0;
        int invalid = 0;
        int rejected = 0;
        int skipped = 0;
        int failed = 0;
        int errors = 0;
        int consecutiveFailed = 0;
        boolean aborted = false;
        for (VisaPushTarget target : candidates) {
            VisaPlanStage stage = VisaPlanStage.of(ChronoUnit.DAYS.between(today, target.expiryDate()));
            if (stage == null || stage == VisaPlanStage.EXPIRED) {
                continue; // 아직 멀거나 이미 지남
            }
            if (notificationRepository.existsByUserIdAndExpiryDateAndStage(target.userId(), target.expiryDate(), stage)) {
                continue; // 이 단계 알림은 이미 보냈다
            }
            targets++;
            try {
                VisaPushService.MemberResult result = visaPushService.notifyMember(target.userId(), target.expiryDate(), stage);
                switch (result) {
                    case SENT -> {
                        sent++;
                        consecutiveFailed = 0;
                    }
                    case INVALID -> { // 구글이 답은 했으니 서버가 죽은 게 아니다
                        invalid++;
                        consecutiveFailed = 0;
                    }
                    case REJECTED -> { // 구글이 4xx로 거절: 구글은 살아 있다. 기록 없이 다음 회차에 다시
                        rejected++;
                        consecutiveFailed = 0;
                    }
                    case FAILED -> { // 구글이 답을 못 함
                        failed++;
                        consecutiveFailed++;
                    }
                    case SKIPPED -> skipped++;
                }
            } catch (RuntimeException e) {
                errors++; // 한 회원의 오류가 전체를 멈추지 않게. 예외 종류 이름만 남긴다(메시지·id 금지)
                log.warn("비자 일정 푸시 알림 처리 오류 1건: {}", e.getClass().getSimpleName());
            }
            if (consecutiveFailed >= MAX_CONSECUTIVE_FAILED_MEMBERS) {
                aborted = true;
                break;
            }
        }
        if (targets > 0) {
            // 대상(여기까지 처리한 회원) = 보냄 + 못 쓰는 토큰 + 거절 + 실패 + 건너뜀 + 오류
            String summary = "비자 일정 푸시 알림: 대상 {}명, 보냄 {}명, 못 쓰는 토큰 {}명, 거절 {}명, 실패 {}명, 건너뜀 {}명, 오류 {}명{}";
            String tail = aborted ? " (연속 실패로 이번 회차 중단, 다음 회차에 다시)" : "";
            if (aborted) {
                log.warn(summary, targets, sent, invalid, rejected, failed, skipped, errors, tail);
            } else {
                log.info(summary, targets, sent, invalid, rejected, failed, skipped, errors, tail);
            }
        }
        return sent;
    }
}
