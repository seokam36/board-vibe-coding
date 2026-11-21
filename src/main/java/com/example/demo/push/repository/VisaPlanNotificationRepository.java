package com.example.demo.push.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.push.domain.VisaPlanNotification;
import com.example.demo.visa.domain.VisaPlanStage;

// 비자 일정 알림 "보낸 기록" 저장소
public interface VisaPlanNotificationRepository extends JpaRepository<VisaPlanNotification, Long> {

    // 그 (회원, 만료일, 단계) 알림을 이미 보냈는지
    boolean existsByUserIdAndExpiryDateAndStage(Long userId, LocalDate expiryDate, VisaPlanStage stage);

    // 기록을 남긴다. 이미 있으면(UNIQUE 충돌) 조용히 넘어간다(ON CONFLICT DO NOTHING) -> 겹쳐 돌아도 오류 없이 한 줄만.
    // 바깥 트랜잭션 없이 부르면 예외(MANDATORY).
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = "insert into visa_plan_notifications (user_id, expiry_date, stage, sent_at) "
            + "values (:userId, :expiryDate, :stage, now()) "
            + "on conflict on constraint uk_visa_plan_notifications_user_expiry_stage do nothing", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("expiryDate") LocalDate expiryDate,
            @Param("stage") String stage);
}
