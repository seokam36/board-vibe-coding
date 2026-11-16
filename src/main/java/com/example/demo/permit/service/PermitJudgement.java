package com.example.demo.permit.service;

import com.example.demo.permit.domain.PermitMachineResult;

// 판정 결과 한 건.
// result: 처음 걸린 규칙의 결과 코드(모두 통과면 ALL_PASSED)
// detectedSchoolId: 학교 칸이 별칭과 일치하면 그 학교 id(다른 항목이 떨어져도 채운다. 관리자 참고용). 불일치·없음이면 null
// normalizedNumber: 정규화한 허가서 번호. ALL_PASSED일 때만 채우고 그 밖에는 null (필요 없는 개인정보를 들고 다니지 않으려고)
public record PermitJudgement(PermitMachineResult result, Long detectedSchoolId, String normalizedNumber) {

    // record 기본 toString은 번호를 그대로 찍으므로 가린다 (PermitProperties와 같은 방식)
    @Override
    public String toString() {
        return "PermitJudgement[result=" + result + ", detectedSchoolId=" + detectedSchoolId
                + ", normalizedNumber=" + (normalizedNumber == null ? "(없음)" : "****") + "]";
    }
}
