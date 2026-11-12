package com.example.demo.photo.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

// 청소부 (S13-g4): 주기적으로 대기 표(photo_delete_queue)에 남은 파일을 마저 지운다.
// 남는 경우: 커밋 뒤 파일 삭제가 실패했거나, 업로드/삭제 도중 서버가 죽었을 때.
// 스케줄은 app.scheduling.enabled=false 이면 켜지지 않는다(테스트 설정). 테스트는 runOnce()를 직접 부른다.
// 처음 성공한 실행 때 INFO 한 줄을 남긴다(배포 뒤 스케줄 동작 확인용). 이후엔 처리한 게 있을 때만 로그.
//
// 트랜잭션 범위(중요): "꺼내기"만 짧은 트랜잭션이고, 파일 지우기(느릴 수 있음)는 트랜잭션 밖에서 한다.
// 파일을 지우는 동안 DB 행 잠금과 연결(풀이 3개뿐)을 쥐고 있지 않으려는 것이다.
// 꺼낼 때 시도 횟수(attempts)를 미리 1 올려 두고 커밋하므로, 서버가 중간에 죽어도 횟수가 남는다.
@Slf4j
@Component
public class PhotoCleanupJob {

    static final int BATCH_SIZE = 100; // 한 번에 처리하는 줄 수
    static final int WARN_ATTEMPTS = 5; // 이만큼 실패하면 눈에 띄게 경고 (계속 재시도는 한다)

    private final NamedParameterJdbcTemplate jdbc;
    private final PhotoDeleteQueue queue;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration minAge;
    private final AtomicBoolean firstRun = new AtomicBoolean(true); // 아직 한 번도 성공 못 했는가 (스케줄러 스레드는 하나지만 안전하게 AtomicBoolean)

    public PhotoCleanupJob(NamedParameterJdbcTemplate jdbc, PhotoDeleteQueue queue, TransactionTemplate tx, Clock clock,
            @Value("${app.photo.cleanup-min-age:PT10M}") Duration minAge) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.tx = tx;
        this.clock = clock;
        this.minAge = minAge;
    }

    // 스케줄러가 부르는 문. 주기·첫 실행 지연은 같은 설정(app.photo.cleanup-interval, 기본 10분)을 쓴다.
    // 예외가 나도 스케줄러가 멈추지 않게 여기서 잡아 로그만 남긴다.
    @Scheduled(fixedDelayString = "${app.photo.cleanup-interval:PT10M}", initialDelayString = "${app.photo.cleanup-interval:PT10M}")
    void scheduledRun() {
        try {
            int count = runOnce();
            // 처음으로 "성공"한 실행에서만 확인용 INFO를 찍고 깃발을 내린다(스케줄이 정말 도는지 알려는 것).
            // 실패로 깃발을 먼저 내리면, 첫 시도가 실패한 뒤엔 성공해도 확인 로그가 영영 안 나오기 때문이다.
            if (firstRun.compareAndSet(true, false)) {
                log.info("사진 청소부 첫 실행: {}개 처리 (이후엔 처리한 게 있을 때만 로그)", count);
            } else if (count > 0) {
                log.info("사진 청소부: {}개 처리", count);
            }
        } catch (RuntimeException e) {
            // 깃발이 아직 서 있으면 "성공한 적 없음"이므로 문구에 "첫 "을 붙여 구분한다(깃발은 그대로 둠)
            log.warn("사진 청소부 {}실행에 실패했습니다(다음 주기에 다시 합니다)", firstRun.get() ? "첫 " : "", e);
        }
    }

    // 한 번 돌린다(최대 BATCH_SIZE줄). 처리한 줄 수를 돌려준다. 테스트에서도 직접 부른다.
    public int runOnce() {
        // "너무 새로운 줄"은 건드리지 않는다: 지금 막 올리는 중인 사진의 쪽지일 수 있어서다 (cutoff = 지금 - minAge)
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(minAge);

        // 1) 짧은 트랜잭션: 줄을 잠그며 꺼내고(SKIP LOCKED: 다른 쪽이 쥔 줄은 건너뜀), 안전 확인을 하고, 횟수를 올린다
        List<Claimed> claimed = tx.execute(status -> claim(cutoff));
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }

        // 2) 트랜잭션 밖: 창고에서 파일 지우기
        List<Long> done = new ArrayList<>();
        for (Claimed c : claimed) {
            if (queue.tryDeleteFile(c.key())) {
                done.add(c.id());
            } else if (c.attempts() >= WARN_ATTEMPTS) {
                log.warn("사진 파일 삭제가 {}번째 실패했습니다. 확인이 필요합니다: {}", c.attempts(), c.key());
            }
        }

        // 3) 짧은 트랜잭션: 지운 줄만 표에서 뺀다 (실패한 줄은 남아 다음 주기에 다시)
        if (!done.isEmpty()) {
            tx.executeWithoutResult(status -> jdbc.update("delete from photo_delete_queue where id in (:ids)",
                    Map.of("ids", done)));
        }
        return claimed.size();
    }

    // 트랜잭션 안: 오래된 줄을 꺼내서 (1) 아직 사진 행이 쓰는 키면 표 줄만 지우고(파일은 그대로), (2) 나머지는 횟수를 올려 돌려준다.
    private List<Claimed> claim(OffsetDateTime cutoff) {
        List<Claimed> rows = jdbc.query("select id, storage_key, attempts from photo_delete_queue "
                + "where created_at < :cutoff order by id limit :limit for update skip locked",
                new MapSqlParameterSource().addValue("cutoff", cutoff).addValue("limit", BATCH_SIZE),
                (rs, i) -> new Claimed(rs.getLong("id"), rs.getString("storage_key"), rs.getInt("attempts")));
        if (rows.isEmpty()) {
            return List.of();
        }

        // 안전 확인: 장부(사진 행)가 아직 가리키는 키라면 파일을 지우면 안 된다 (잘못 적힌 쪽지 방어)
        List<String> keys = rows.stream().map(Claimed::key).toList();
        Set<String> inUse = new HashSet<>(jdbc.queryForList("select storage_key from market_item_photos where storage_key in (:keys) "
                + "union select thumb_key from market_item_photos where thumb_key in (:keys)", Map.of("keys", keys), String.class));

        List<Long> staleIds = new ArrayList<>(); // 쪽지만 버릴 줄
        List<Claimed> targets = new ArrayList<>(); // 파일을 지울 줄
        for (Claimed row : rows) {
            if (inUse.contains(row.key())) {
                staleIds.add(row.id());
            } else {
                targets.add(row);
            }
        }
        if (!staleIds.isEmpty()) {
            log.warn("사진 삭제 대기 표에 아직 쓰이는 파일이 적혀 있어 줄만 지웁니다: {}건", staleIds.size());
            jdbc.update("delete from photo_delete_queue where id in (:ids)", Map.of("ids", staleIds));
        }
        if (targets.isEmpty()) {
            return List.of();
        }
        jdbc.update("update photo_delete_queue set attempts = attempts + 1 where id in (:ids)",
                Map.of("ids", targets.stream().map(Claimed::id).toList()));
        // 돌려주는 attempts는 방금 올린 값(= 이번 시도까지 센 횟수)
        return targets.stream().map(c -> new Claimed(c.id(), c.key(), c.attempts() + 1)).toList();
    }

    private record Claimed(long id, String key, int attempts) {
    }
}
