package com.example.demo.user.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 회원 한 명을 나타내는 엔티티. user는 PostgreSQL 예약어라 테이블 이름은 users로 한다.
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // unique 제약: 같은 아이디 동시 가입을 DB가 마지막으로 막아 준다
    @Column(nullable = false, unique = true, length = 20)
    private String username;

    // 해시된 비밀번호({bcrypt} 접두어 포함). 원문은 절대 저장하지 않는다.
    @Column(nullable = false, length = 100)
    private String password;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public User(String username, String encodedPassword) {
        this.username = username;
        this.password = encodedPassword;
    }

    // 저장 직전에 가입 시각 기록 (Post와 같은 방식: UTC, 마이크로초)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
