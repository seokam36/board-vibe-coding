package com.example.demo.visa.domain;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 내 비자 일정 (visa_plans 표). 회원당 1행이고 체류 만료일만 가진다.
// 저장(등록·덮어쓰기)은 동시 요청에도 안전하도록 VisaPlanRepository.upsert(SQL 한 문장)가 하므로
// 이 엔티티는 읽기·삭제용이다. 회원은 객체 연결 없이 id만 들고 있다(응답에 회원 정보가 필요 없어서).
@Entity
@Table(name = "visa_plans")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class VisaPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "expiry_date", nullable = false)
    private LocalDate expiryDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
