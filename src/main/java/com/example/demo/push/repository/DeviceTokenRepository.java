package com.example.demo.push.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.push.domain.DeviceToken;

// 기기 토큰 저장소. 쓰기 메서드는 전부 바깥 트랜잭션이 있어야 한다(MANDATORY).
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

    // 토큰을 등록하거나 주인을 바꾼다. "없으면 INSERT, 있으면 주인·updated_at 변경"을 SQL 한 문장으로 해서
    // 같은 토큰의 PUT 두 개가 동시에 와도 UNIQUE 위반(500) 없이 끝난다.
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "insert into device_tokens (user_id, token, created_at, updated_at) "
            + "values (:userId, :token, now(), now()) "
            + "on conflict on constraint uk_device_tokens_token "
            + "do update set user_id = excluded.user_id, updated_at = now()", nativeQuery = true)
    int upsert(@Param("userId") Long userId, @Param("token") String token);

    // 한 회원이 maxCount개를 넘으면, 가장 오래된(updated_at, 같으면 id 작은 것) 것부터 지워 maxCount개만 남긴다.
    // 최신 것부터 줄 세워서(desc) maxCount개를 건너뛴(offset) 나머지가 지울 대상이다.
    // 바깥 delete에도 user_id를 걸어, 그 사이 다른 회원에게 주인이 넘어간 행은 지우지 않는다.
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "delete from device_tokens where user_id = :userId and id in ("
            + "select id from device_tokens where user_id = :userId "
            + "order by updated_at desc, id desc offset :maxCount)", nativeQuery = true)
    int trimToMax(@Param("userId") Long userId, @Param("maxCount") int maxCount);

    // 내 토큰일 때만 지운다(없거나 남의 것이면 0행). 지운 행 수를 돌려준다.
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "delete from device_tokens where user_id = :userId and token = :token", nativeQuery = true)
    int deleteByUserIdAndToken(@Param("userId") Long userId, @Param("token") String token);

    // 그 회원의 토큰을 전부 지운다(비밀번호 변경 때).
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "delete from device_tokens where user_id = :userId", nativeQuery = true)
    int deleteAllByUserId(@Param("userId") Long userId);

    // 그 회원의 토큰 값들 (알림 보낼 때 읽는다. 짧은 읽기라 연결을 바로 돌려준다)
    @Query("select d.token from DeviceToken d where d.userId = :userId order by d.id")
    List<String> findTokensByUserId(@Param("userId") Long userId);
}
