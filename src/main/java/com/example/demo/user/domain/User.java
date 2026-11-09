package com.example.demo.user.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.example.demo.school.domain.School;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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

    // 화면에 보여 줄 이름(글 작성자 표시용). 로그인 아이디를 숨기려고 따로 둔다. unique로 중복 닉네임을 DB가 막는다.
    @Column(nullable = false, unique = true, length = 20)
    private String nickname;

    // 해시된 비밀번호({bcrypt} 접두어 포함). 원문은 절대 저장하지 않는다.
    @Column(nullable = false, length = 100)
    private String password;

    // 역할. 가입은 항상 USER이고, ADMIN은 DB에서만 지정한다(생성자·API로 정할 수 없다). enum 이름을 문자열로 저장.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // 학교 인증(S10). 인증 전에는 셋 다 null. 인증 처리(코드 확인)는 EmailVerificationService가 UserRepository.markVerified로 한다.
    // 인증한 학교(한 계정에 1개, 만료 없음). 지연 로딩이라 읽을 때 쿼리에서 함께 가져온다(UserRepository의 EntityGraph).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verified_school_id")
    private School verifiedSchool;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    // 학교 메일을 소문자로 바꾼 SHA-256 해시(hex 64자). 원문은 저장하지 않고, UNIQUE라 같은 메일로 여러 계정 인증을 막는다.
    @Column(name = "school_email_hash", length = 64)
    private String schoolEmailHash;

    public User(String username, String nickname, String encodedPassword) {
        this.username = username;
        this.nickname = nickname;
        this.password = encodedPassword;
        this.role = UserRole.USER;
    }

    // 저장 직전에 가입 시각 기록 (Post와 같은 방식: UTC, 마이크로초)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
