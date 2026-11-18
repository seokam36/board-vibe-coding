// 표준입학허가서 반려 사유 (회원 화면과 관리자 화면이 같이 쓴다)

// 반려 사유 코드 → 안내 문구 (서버 코드 7개. 모르는 코드는 OTHER 문구)
const permitRejectReasons = <String, String>{
  'BLURRY': '사진이 흐려서 글자를 읽을 수 없어요',
  'NOT_PERMIT': '표준입학허가서가 아니에요',
  'NOT_MASKED': '가려야 할 정보가 보여요. 가리고 다시 올려 주세요',
  'UNSUPPORTED_SCHOOL': '아직 지원하지 않는 학교예요',
  'DUPLICATE': '이미 다른 계정에서 인증한 허가서예요',
  // 14일: 서버 app.permit.pending-ttl과 같이 바꾼다
  'EXPIRED': '14일 안에 확인하지 못했어요. 다시 신청해 주세요',
  'OTHER': '확인할 수 없었어요. 다시 신청해 주세요',
};

// 관리자가 고를 수 있는 반려 사유 코드 (드롭다운 순서 그대로).
// EXPIRED(14일 지나 자동 반려)는 시스템 전용이라 일부러 뺀다.
const adminRejectReasonCodes = <String>[
  'BLURRY',
  'NOT_PERMIT',
  'NOT_MASKED',
  'UNSUPPORTED_SCHOOL',
  'DUPLICATE',
  'OTHER',
];
