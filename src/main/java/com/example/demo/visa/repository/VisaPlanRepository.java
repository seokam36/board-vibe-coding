package com.example.demo.visa.repository;

import java.time.LocalDate;
import java.util.List;
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

    // 푸시 알림 후보 (S24-c): 유학생 인증이 지금 유효하고(verified_school_id 있음) 기기 토큰이 1개 이상인 회원 중
    // 만료일이 [from, to] 안인 사람. 단계(PREPARE·CHECK·URGENT)와 "이미 보냈는지"는 호출한 쪽이 고른다.
    // 같은 SQL로 말하면: select p.user_id, p.expiry_date from visa_plans p join users u on u.id = p.user_id
    //   where u.verified_school_id is not null and p.expiry_date between :from and :to
    //   and exists (select 1 from device_tokens d where d.user_id = p.user_id) order by p.user_id
    @Query("select new com.example.demo.visa.repository.VisaPushTarget(p.userId, p.expiryDate) "
            + "from VisaPlan p, User u "
            + "where u.id = p.userId and u.verifiedSchool is not null "
            + "and p.expiryDate between :from and :to "
            + "and exists (select 1 from DeviceToken d where d.userId = p.userId) "
            + "order by p.userId")
    List<VisaPushTarget> findPushCandidates(@Param("from") LocalDate from, @Param("to") LocalDate to);

    // 보내기 직전 재확인 (S24-c): 그 회원이 지금도 유학생 인증이 유효하고, 비자 일정 만료일이 후보로 뽑았을 때와 같은가
    @Query("select count(p) from VisaPlan p, User u "
            + "where u.id = p.userId and p.userId = :userId and p.expiryDate = :expiryDate and u.verifiedSchool is not null")
    long countStillTarget(@Param("userId") Long userId, @Param("expiryDate") LocalDate expiryDate);
}
