package com.example.demo.photo.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.demo.photo.repository.PhotoStorage;

import lombok.extern.slf4j.Slf4j;

// "창고에서 지워야 할 파일" 대기 표(photo_delete_queue)를 다루는 도우미 (S13-g4).
// 비유: 이사할 때 "버릴 물건 목록"을 먼저 적어 두고, 버린 뒤에 목록에서 줄을 긋는다.
//       버리다 말고 집이 무너져도(서버가 죽어도) 목록이 남아 있으니 나중에 청소부(PhotoCleanupJob)가 마저 버린다.
//
// 쓰는 방법 두 가지:
//  - 장부(DB)에서 사진 행을 지우는 트랜잭션 "안에서" enqueue*() 로 키를 적는다 -> 장부 삭제와 목록 적기가 한 덩어리로 커밋/롤백된다.
//  - 커밋이 끝난 "뒤에" deleteFilesAndDequeue() 로 파일을 지우고 지운 키를 목록에서 뺀다. 실패해도 예외를 던지지 않는다(목록에 남아 청소부가 처리).
// 창고 delete는 반드시 이 클래스를 거친다(테스트가 창고를 가짜로 바꿔 끼우는 지점도 여기 한 곳).
@Slf4j
@Component
public class PhotoDeleteQueue {

    private final NamedParameterJdbcTemplate jdbc;
    private final PhotoStorage photoStorage;
    private final TransactionTemplate tx;

    public PhotoDeleteQueue(NamedParameterJdbcTemplate jdbc, PhotoStorage photoStorage, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.photoStorage = photoStorage;
        this.tx = tx;
    }

    // 키들을 대기 표에 적는다. 한 문장(다중 행 INSERT)이라 둘 다 들어가거나 둘 다 안 들어간다.
    // 바깥 트랜잭션이 있으면 그 안에 끼고(장부 변경과 같이 커밋), 없으면 이 호출이 자기 트랜잭션으로 바로 커밋한다.
    public void enqueue(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("insert into photo_delete_queue (storage_key) values ");
        Map<String, Object> values = new HashMap<>();
        int i = 0;
        for (String key : keys) {
            sql.append(i == 0 ? "" : ", ").append("(:k").append(i).append(')');
            values.put("k" + i, key);
            i++;
        }
        tx.executeWithoutResult(status -> jdbc.update(sql.toString(), values));
    }

    // 글 하나의 사진 파일 이름(본 사진·썸네일)을 전부 대기 표에 적고, 적은 키 목록을 돌려준다.
    // INSERT ... SELECT 한 문장이라 "읽고 -> 적기" 사이에 사진이 끼어들 틈이 없다. 반드시 글을 지우는 트랜잭션 안에서 부른다.
    public List<String> enqueueItemPhotos(Long itemId) {
        return jdbc.queryForList("insert into photo_delete_queue (storage_key) "
                + "select v.k from market_item_photos p cross join lateral (values (p.storage_key), (p.thumb_key)) as v(k) "
                + "where p.market_item_id = :itemId returning storage_key", Map.of("itemId", itemId), String.class);
    }

    // 판매자가 올린 모든 글의 사진 파일 이름을 대기 표에 적고 키 목록을 돌려준다 (회원 탈퇴용. 탈퇴 트랜잭션 안에서 부른다).
    // 먼저 그 회원의 글 행을 잠근다: 사진 올리기도 글 행을 잠그고 시작하므로, 이 잠금이 있으면 "적은 뒤에 새 사진이 끼어드는" 일이 없다.
    // (id 순서로 잠가 다른 요청과 잠금 순서가 엇갈려 서로 기다리는 일을 막는다)
    public List<String> enqueueSellerPhotos(Long sellerId) {
        jdbc.queryForList("select id from market_items where seller_id = :sellerId order by id for update",
                Map.of("sellerId", sellerId), Long.class);
        return jdbc.queryForList("insert into photo_delete_queue (storage_key) "
                + "select v.k from market_item_photos p join market_items m on m.id = p.market_item_id "
                + "cross join lateral (values (p.storage_key), (p.thumb_key)) as v(k) "
                + "where m.seller_id = :sellerId returning storage_key", Map.of("sellerId", sellerId), String.class);
    }

    // 대기 표에서 키들의 줄을 긋는다 (올리기 성공 때 "쪽지 떼기", 파일을 지운 뒤 정리에 쓴다).
    // 바깥 트랜잭션이 있으면 그 안에 끼고, 없으면 자기 트랜잭션으로 커밋한다.
    public void dequeue(Collection<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> jdbc.update("delete from photo_delete_queue where storage_key in (:keys)",
                Map.of("keys", keys)));
    }

    // 커밋 뒤에 부른다: 파일을 하나씩 지우고, 지운(또는 원래 없던) 키만 대기 표에서 뺀다.
    // 지우기나 표 정리가 실패해도 예외를 던지지 않는다: 응답을 바꾸면 안 되고, 못 지운 키는 표에 남아 청소부가 다시 시도한다.
    // 파일 하나가 실패해도 다음 파일은 계속 지운다.
    public void deleteFilesAndDequeue(Collection<String> keys) {
        List<String> deleted = keys.stream().filter(this::tryDeleteFile).toList();
        try {
            dequeue(deleted);
        } catch (RuntimeException e) {
            // 표 줄을 못 그어도 파일은 이미 지워졌다. 줄은 남지만 청소부가 "이미 없는 파일 지우기"로 곧 정리한다.
            log.warn("사진 삭제 대기 표 정리에 실패했습니다(청소부가 다시 처리합니다): {}", deleted, e);
        }
    }

    // 창고에서 파일 하나를 지운다. 성공(이미 없던 파일 포함)하면 true, 실패하면 로그만 남기고 false.
    boolean tryDeleteFile(String key) {
        try {
            photoStorage.delete(key);
            return true;
        } catch (RuntimeException e) {
            log.warn("사진 파일 삭제에 실패했습니다(대기 표에 남겨 둡니다): {}", key, e);
            return false;
        }
    }
}
