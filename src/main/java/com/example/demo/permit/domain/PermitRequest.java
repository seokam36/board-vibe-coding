package com.example.demo.permit.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 표준입학허가서 인증 신청 한 건 (permit_requests 표). 사진 파일은 창고(PhotoStorage)에 있고 여기엔 창고 이름(key)만 둔다.
// 다른 표(회원·학교)는 객체가 아니라 id만 들고 있다: 신청을 읽을 때마다 같이 읽어 올 필요가 없어서.
// 이번 단계(S20-a)는 "신청"만 만들어서 항상 PENDING이다. 승인·거절·기계 판독 칸은 S20-b·S20-d가 채운다.
@Entity
@Table(name = "permit_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA용 기본 생성자
public class PermitRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PermitStatus status;

    // 허가서 사진 창고 이름. 서버 안에서만 쓰고 응답에 내보내지 않는다. 결과가 나와 사진을 지우면 null
    @Column(name = "photo_key", length = 255)
    private String photoKey;

    @Column(name = "content_type", length = 20)
    private String contentType;

    @Column(name = "school_id")
    private Long schoolId;

    @Column(name = "detected_school_id")
    private Long detectedSchoolId;

    @Column(name = "machine_result", length = 30)
    private String machineResult;

    @Column(name = "auto_decided", nullable = false)
    private boolean autoDecided;

    @Column(name = "permit_number_hash", length = 64)
    private String permitNumberHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "reject_reason", length = 30)
    private PermitRejectReason rejectReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private Long decidedBy;

    // 새 신청: 항상 대기(PENDING) 상태로 만든다
    public PermitRequest(Long userId, String photoKey, String contentType) {
        this.userId = userId;
        this.status = PermitStatus.PENDING;
        this.photoKey = photoKey;
        this.contentType = contentType;
    }

    // 저장 직전에 신청 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
