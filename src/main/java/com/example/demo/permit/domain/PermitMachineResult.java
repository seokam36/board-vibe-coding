package com.example.demo.permit.domain;

// 기계(판독기 + 판정 규칙)가 허가서를 보고 내린 결과 코드. permit_requests.machine_result(varchar 30)에 이름 그대로 저장한다.
// 이름은 모두 30자 이내여야 한다(칸 길이). 규칙 판정은 위에서부터 처음 걸린 것 하나만 돌려준다.
public enum PermitMachineResult {
    UNREADABLE,          // 글자를 못 읽음(판독기 실패·빈 결과). 판정 규칙이 아니라 신청 처리(S20-d2)가 정한다
    NO_TITLE,            // 허가서 제목("표준입학허가서" / "Certificate of Admission")이 없음
    NATIONALITY_UNCLEAR, // 국적이 한국 계열이거나, 국적 칸이 없거나 비어 있음
    SCHOOL_NOT_MATCHED,  // 학교명 칸이 우리 별칭 표의 학교와 정확히 일치하지 않음
    NUMBER_NOT_FOUND,    // 허가서 번호 칸이 없거나 번호가 너무 짧음(4자 미만)
    NUMBER_DUPLICATE,    // 같은 번호가 이미 승인됐거나 관리자가 취소한 번호(S21-b). 중복 확인은 DB가 필요해 신청 처리(S20-d2)가 정한다
    ALL_PASSED           // 4가지 규칙을 모두 통과
}
