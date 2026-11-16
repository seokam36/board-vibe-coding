package com.example.demo.permit.dto;

import java.time.Instant;

import com.example.demo.permit.domain.PermitRejectReason;
import com.example.demo.permit.domain.PermitRequest;
import com.example.demo.permit.domain.PermitStatus;

// 관리자용 허가서 신청 응답 (S20-b). 사진 key·번호 해시·content type은 일부러 담지 않는다(hasPhoto만).
// 값이 없는 칸도 null로 나간다(칸이 항상 있어 화면이 다루기 쉽다).
// detectedSchool*·machineResult·autoDecided는 신청 직후 자동 심사(S20-d2)가 채운다(아직 안 채워졌거나 심사 전 옛 행이면 null·false).
public record AdminPermitRequestResponse(Long id, Long userId, String nickname, PermitStatus status, Instant createdAt,
        Instant decidedAt, Long schoolId, String schoolName, PermitRejectReason rejectReason, Long detectedSchoolId,
        String detectedSchoolName, String machineResult, boolean autoDecided, boolean hasPhoto) {

    // 닉네임·학교 이름은 호출한 쪽이 미리 읽어 넘긴다(목록에서 건마다 쿼리가 나가지 않게)
    public static AdminPermitRequestResponse of(PermitRequest r, String nickname, String schoolName,
            String detectedSchoolName) {
        return new AdminPermitRequestResponse(r.getId(), r.getUserId(), nickname, r.getStatus(), r.getCreatedAt(),
                r.getDecidedAt(), r.getSchoolId(), schoolName, r.getRejectReason(), r.getDetectedSchoolId(),
                detectedSchoolName, r.getMachineResult(), r.isAutoDecided(), r.getPhotoKey() != null);
    }
}
