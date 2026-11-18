package com.example.demo.permit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.demo.permit.domain.PermitStatus;
import com.example.demo.permit.repository.PermitRequestRepository;

import lombok.extern.slf4j.Slf4j;

// 허가서 기한 만료 정리 (S20-c): 대기(PENDING)가 ttl(app.permit.pending-ttl, 설정에 14일)을 넘긴 신청을 주기적으로(켜지고 3분 뒤 + 1시간마다) 자동 반려(EXPIRED)하고 사진을 지운다.
// 스케줄은 app.scheduling.enabled=false 이면 켜지지 않는다(테스트 설정). 테스트는 runOnce(now)를 직접 부른다.
// 실행 주기: 서버가 켜지고 app.permit.expiry-initial-delay(기본 3분) 뒤 첫 실행, 그 뒤 한 번 끝나고 app.permit.expiry-interval(기본 1시간)마다.
// (운영 서버는 평소 꺼 두므로 "매일 새벽 한 번" 방식은 거의 안 돌아서 사진 청소부처럼 켜진 뒤 바로 도는 방식을 쓴다)
// 한 번 실행에서 app.permit.expiry-batch-size건(기본 100)씩 오래된 순으로 끝까지 반복한다(총 MAX_PER_RUN건 상한).
// 건마다 AdminPermitService.expireIfStale이 짧은 트랜잭션으로 처리한다(관리자 반려와 같은 잠금 순서). 이 클래스는 트랜잭션을 잡지 않는다.
// 로그에는 건수만 남긴다(requestId·회원 id·사진 key는 남기지 않는다).
@Slf4j
@Component
public class PermitExpiryJob {

    static final int MAX_PER_RUN = 10_000; // 한 번 실행에서 훑는 최대 건수(안전용)

    private final PermitRequestRepository permitRequestRepository;
    private final AdminPermitService adminPermitService;
    private final Clock clock;
    private final Duration pendingTtl;
    private final int batchSize;

    public PermitExpiryJob(PermitRequestRepository permitRequestRepository, AdminPermitService adminPermitService,
            Clock clock, @Value("${app.permit.pending-ttl}") Duration pendingTtl,
            @Value("${app.permit.expiry-batch-size:100}") int batchSize) {
        // 잘못된 설정은 기동 때 바로 실패시킨다
        if (pendingTtl.isZero() || pendingTtl.isNegative()) {
            throw new IllegalArgumentException("app.permit.pending-ttl은 0보다 커야 합니다: " + pendingTtl);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("app.permit.expiry-batch-size는 1 이상이어야 합니다: " + batchSize);
        }
        this.permitRequestRepository = permitRequestRepository;
        this.adminPermitService = adminPermitService;
        this.clock = clock;
        this.pendingTtl = pendingTtl;
        this.batchSize = batchSize;
    }

    // 스케줄러가 부르는 문. 예외가 나도 스케줄러가 멈추지 않게 여기서 잡아 로그만 남긴다.
    @Scheduled(fixedDelayString = "${app.permit.expiry-interval:PT1H}", initialDelayString = "${app.permit.expiry-initial-delay:PT3M}")
    void scheduledRun() {
        try {
            runOnce(Instant.now(clock));
        } catch (RuntimeException e) {
            log.warn("허가서 기한 만료 정리에 실패했습니다(다음 주기에 다시 합니다)", e);
        }
    }

    // 한 번 돌린다. 반려한 건수를 돌려준다. 건별 실패는 잡고 다음 건으로 간다. 테스트에서도 직접 부른다.
    public int runOnce(Instant now) {
        Instant cutoff = now.minus(pendingTtl).truncatedTo(ChronoUnit.MICROS); // 이보다 먼저 만들어진 대기 신청이 대상
        Instant decidedAt = now.truncatedTo(ChronoUnit.MICROS);
        int expired = 0;
        int failed = 0;
        int scanned = 0;
        long lastId = 0; // 앞 묶음의 마지막 id. 이보다 큰 것만 이어서 읽어 실패 건이 다시 뽑히지 않게 한다
        while (scanned < MAX_PER_RUN) {
            List<Long> ids = permitRequestRepository.findIdsByStatusAndCreatedAtBefore(PermitStatus.PENDING, cutoff,
                    lastId, Limit.of(batchSize));
            if (ids.isEmpty()) {
                break;
            }
            for (Long id : ids) {
                lastId = id;
                scanned++;
                try {
                    if (adminPermitService.expireIfStale(id, cutoff, decidedAt)) {
                        expired++;
                    }
                } catch (RuntimeException e) {
                    failed++; // 한 건 실패가 전체를 멈추지 않게. 예외 종류 이름만 남긴다(메시지·id·key 금지)
                    log.warn("허가서 기한 만료 처리 실패 1건: {}", e.getClass().getSimpleName());
                }
            }
            if (ids.size() < batchSize) {
                break; // 마지막 묶음
            }
        }
        if (expired > 0 || failed > 0) {
            log.info("허가서 기한 만료 정리: {}건 (실패 {}건)", expired, failed);
        }
        return expired;
    }
}
