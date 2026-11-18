// ---- 문구 (화면에 보이는 글자는 여기 한 곳에 모은다) ----

// 사진을 수집하는 것에 대한 동의 문구 (체크박스 옆에 그대로 보인다)
const permitConsentText =
    '표준입학허가서 사진을 유학생·학교 확인 목적으로 수집합니다. 사진은 심사가 끝나면 바로 지우고, 늦어도 14일 뒤 삭제합니다. '
    '허가서 번호는 중복 확인용 암호화 값(해시)만 보관합니다. 사진은 글자 확인을 위해 Google Cloud Vision으로 전송될 수 있습니다. '
    '언제든 철회할 수 있습니다.';

// 사진에서 가려야 할 것 / 보이게 둘 것 (두 줄로 나눠 보여 준다)
const permitMaskHide = '가려 주세요: 여권번호, 생년월일, 이메일, 재정 금액';
const permitMaskShow = '보이게 두세요: 국적, 학교명, 표준입학허가서 번호';

// 반려 사유 문구(permitRejectReasons)는 ../permit/permit_reject_reasons.dart로 옮겼다

// 신청(POST) 실패 상태코드별 문구 (401은 표에 두지 않는다 → describeError가 로그인 안내로 바꾼다)
const permitSubmitErrors = <int, String>{
  400: '사진을 확인해 주세요. 동의에 체크하고 JPEG·PNG 사진(10MB 이하)인지, 손상되지 않았는지 봐 주세요',
  403: '신청하지 못했어요. 새로고침 후 다시 시도해 주세요',
  409: '이미 인증됐거나 심사를 기다리는 신청이 있어요',
  413: '사진은 10MB 이하여야 해요',
  429: '오늘은 신청을 3번 다 썼어요. 내일 다시 신청해 주세요',
  503: '서버가 바빠요. 잠시 뒤 다시 시도해 주세요',
};
const permitSubmitFailed = '신청하지 못했어요. 잠시 뒤 다시 시도해 주세요';
// 시간 초과: 서버가 이미 접수했을 수도 있어서, 상태를 다시 확인한다고 알린다
const permitTimeoutMessage = '시간이 오래 걸려 멈췄어요. 접수됐는지 상태를 다시 확인할게요';
const permitProgressMessage = '확인 중이에요. 최대 몇십 초 걸릴 수 있어요';
const permitLoadFailed = '인증 상태를 불러오지 못했어요';
// 사전 검사(브라우저에서 미리 거르는 것)
const permitTypeOnly = '사진은 JPEG·PNG만 올릴 수 있어요';
const permitTooLarge = '사진은 10MB 이하여야 해요';
// 로그인 중에 승인됐는데 회원 정보를 아직 못 받았을 때
const permitApprovedReload = '인증이 승인됐어요. 새로고침하면 인증 표시가 나타나요';
// 관리자가 인증을 취소한 회원에게 (사유는 반려 사유 문구를 재사용한다)
const permitRevokedMessage = '인증이 취소됐어요. 허가서를 다시 올려 심사를 받아 주세요';
// 실패 뒤 상태를 다시 확인해 봤을 때의 결과 문구
const permitNotReceived = '접수되지 않았어요. 다시 신청해 주세요';
const permitRecheckFailed = '접수됐는지 확인하지 못했어요. 새로고침해서 확인해 주세요';

// 신청 요청의 제한 시간 (5분). 웹에서는 업로드를 포함한 요청 전체의 제한 시간으로 쓰인다
// (dio 웹 어댑터가 xhr.timeout = connectTimeout + receiveTimeout 으로 건다).
// 서버 처리(사진 저장 + 글자 읽기 최대 약 10초)는 짧고 대부분이 업로드 시간이다.
// 2분이면 느린 회선(약 0.5Mbps)에서 10MB 사진이 매번 시간 초과라 중고 사진 올리기(photo_upload.dart)와 같은 5분으로 둔다 (S20-h 결정)
const permitSubmitTimeout = Duration(minutes: 5);

// ---- 철회·해제 (DELETE) 문구 ----
const permitWithdrawConfirm = '신청을 철회할까요?';
const permitUnlinkConfirm =
    '인증을 해제하면 글쓰기·댓글·공감·중고·쪽지를 쓸 수 없어요. 다시 쓰려면 허가서를 다시 올려 심사를 받아야 해요. 해제할까요?';
// 철회·해제 실패 상태코드별 문구 (401은 표에 두지 않는다 → describeError가 로그인 안내로 바꾼다)
const permitRemoveErrors = <int, String>{
  403: '요청이 거절됐어요. 새로고침 후 다시 시도해 주세요',
  404: '이미 철회되었거나 인증이 없어요',
};
// 해제는 됐는데 회원 정보를 못 받아 뱃지가 남았을 때
const permitUnlinkReload = '해제됐어요. 새로고침해 주세요.';
const permitRemoveFailed = '처리하지 못했어요. 잠시 뒤 다시 시도해 주세요';
// 철회·해제 요청의 제한 시간 (공용 dio에는 시간 제한이 없어서 요청마다 건다)
const permitRemoveTimeout = Duration(seconds: 30);
