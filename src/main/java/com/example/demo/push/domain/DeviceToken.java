package com.example.demo.push.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 기기 토큰 = 푸시 알림을 보낼 "휴대폰 주소" (device_tokens 표). 회원 한 명이 여러 대(최대 10개)를 가질 수 있다.
// 저장(등록·주인 바꾸기)은 동시 요청에도 안전하도록 DeviceTokenRepository.upsert(SQL 한 문장)가 하므로
// 이 엔티티는 읽기용이다. 회원은 객체 연결 없이 id만 들고 있다.
// 토큰 값은 비밀 값처럼 다룬다: toString을 만들지 않고(@ToString 없음), 로그에도 남기지 않는다.
@Entity
@Table(name = "device_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class DeviceToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "token", nullable = false, length = 4096)
    private String token;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
