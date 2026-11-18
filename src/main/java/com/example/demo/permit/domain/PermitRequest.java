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
// 신청(S20-a)은 항상 PENDING으로 만들어진다. 승인·반려는 관리자(S20-b, approve·reject)가 채우고, 기계 판독 칸과 자동 승인(autoApprove)은 S20-d2가 채운다.
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

    // 관리자 승인 (S20-b): 상태·학교·번호 해시·결정 정보를 채우고 사진 칸을 비운다(파일 삭제는 서비스가 따로 한다).
    // 호출 전에 서비스가 신청 행을 FOR UPDATE로 잠그고 PENDING인지 확인한다.
    public void approve(Long schoolId, String permitNumberHash, Long adminId, Instant now) {
        this.status = PermitStatus.APPROVED;
        this.schoolId = schoolId;
        this.permitNumberHash = permitNumberHash;
        this.decidedAt = now;
        this.decidedBy = adminId;
        this.photoKey = null;
        this.contentType = null;
    }

    // 기계 자동 승인 (S20-d2): 관리자 승인과 같지만 결정한 관리자가 없고(decidedBy는 NULL) autoDecided=true, 판정 결과 ALL_PASSED를 남긴다.
    // 학교는 기계가 찾은 학교(detectedSchoolId)와 같다. 호출 전에 서비스가 신청 행을 FOR UPDATE로 잠그고 PENDING인지 확인한다.
    public void autoApprove(Long schoolId, String permitNumberHash, Instant now) {
        this.status = PermitStatus.APPROVED;
        this.schoolId = schoolId;
        this.detectedSchoolId = schoolId;
        this.machineResult = PermitMachineResult.ALL_PASSED.name();
        this.autoDecided = true;
        this.permitNumberHash = permitNumberHash;
        this.decidedAt = now;
        this.decidedBy = null;
        this.photoKey = null;
        this.contentType = null;
    }

    // 관리자 반려 (S20-b): 상태·이유·결정 정보를 채우고 사진 칸을 비운다
    public void reject(PermitRejectReason reason, Long adminId, Instant now) {
        this.status = PermitStatus.REJECTED;
        this.rejectReason = reason;
        this.decidedAt = now;
        this.decidedBy = adminId;
        this.photoKey = null;
        this.contentType = null;
    }

    // 관리자 인증 취소 (S21-a): 승인된 신청을 REVOKED로 바꾼다. 사유는 reject_reason 칸에 저장하고,
    // decidedAt·decidedBy는 "취소한 시각·관리자"로 덮어쓴다(승인 기록은 새 칸 없이 사라지는 대신 상태로 남는다).
    // 호출 전에 서비스가 신청 행을 FOR UPDATE로 잠그고 APPROVED인지 확인한다. 사진은 승인 때 이미 비워졌다.
    public void revoke(PermitRejectReason reason, Long adminId, Instant now) {
        this.status = PermitStatus.REVOKED;
        this.rejectReason = reason;
        this.decidedAt = now;
        this.decidedBy = adminId;
    }

    // 저장 직전에 신청 시각 기록 (DB 정밀도인 마이크로초로 맞춘다)
    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
