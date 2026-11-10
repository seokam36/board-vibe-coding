package com.example.demo.visa.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.visa.domain.VisaPlan;

// 내 비자 일정 저장소
public interface VisaPlanRepository extends JpaRepository<VisaPlan, Long> {

    // 그 회원의 일정 (없으면 빈 값)
    Optional<VisaPlan> findByUserId(Long userId);

    // 등록하거나 덮어쓴다. "없으면 INSERT, 있으면 UPDATE"를 SQL 한 문장으로 해서
    // 같은 회원의 PUT 두 개가 동시에 와도 UNIQUE 위반(500) 없이 나중 값이 남는다.
    // 회원이 이미 탈퇴했으면 FK 위반이 나므로 호출한 서비스가 401로 바꾼다.
    // 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "insert into visa_plans (user_id, expiry_date, created_at, updated_at) "
            + "values (:userId, :expiryDate, now(), now()) "
            + "on conflict on constraint uk_visa_plans_user "
            + "do update set expiry_date = excluded.expiry_date, updated_at = now()", nativeQuery = true)
    int upsert(@Param("userId") Long userId, @Param("expiryDate") LocalDate expiryDate);

    // 삭제하고 지운 행 수를 돌려준다(0이면 원래 없었음 -> 404). 바깥 트랜잭션 필요(MANDATORY).
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional(propagation = Propagation.MANDATORY)
    @Query("delete from VisaPlan p where p.userId = :userId")
    int deleteByUserIdReturningCount(@Param("userId") Long userId);
}
