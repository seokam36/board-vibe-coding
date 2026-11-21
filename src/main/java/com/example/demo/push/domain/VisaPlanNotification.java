package com.example.demo.push.domain;

import java.time.Instant;
import java.time.LocalDate;

import com.example.demo.visa.domain.VisaPlanStage;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 비자 일정 푸시 알림을 "보냈다"는 기록 (visa_plan_notifications 표). (회원, 만료일, 단계) 한 줄 = 그 단계 알림을 이미 보냈다는 뜻.
// 저장은 VisaPlanNotificationRepository.insertIfAbsent(SQL 한 문장)가 하므로 이 엔티티는 읽기용이다. 회원은 id만 들고 있다.
@Entity
@Table(name = "visa_plan_notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class VisaPlanNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "expiry_date", nullable = false, updatable = false)
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, updatable = false, length = 20)
    private VisaPlanStage stage;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;
}
