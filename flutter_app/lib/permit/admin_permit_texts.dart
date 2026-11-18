// 관리자 "인증 심사" 화면의 문구·상수 (화면 파일이 길어져 따로 모았다)

const permitListFailed = '인증 신청 목록을 불러오지 못했습니다';
const permitPhotoFailed = '사진을 불러오지 못했어요';
const permitPhotoGone = '사진이 없어요(이미 처리되었을 수 있어요)';
const permitPhotoDecodeFailed = '사진을 표시할 수 없어요';
const adminPermitTimeout = Duration(seconds: 30); // 요청마다 거는 응답 제한
const permitAdminForbidden = '처리하지 못했어요. 새로고침 후 다시 시도해 주세요';
const permitAdminGone = '이미 사라진 신청이에요 (철회했거나 탈퇴했을 수 있어요)';
const permitAlreadyHandled = '이미 처리된 신청이에요';
const permitAdminTimeout = '처리됐는지 확인하지 못했어요. 목록에서 확인해 주세요'; // 재조회도 실패할 수 있어 "다시 불러왔어요"라고 단정하지 않는다
const permitOwnRequest = '본인 신청은 직접 심사할 수 없어요';
const permitRefreshFailed = '목록을 다시 불러오지 못했어요'; // 처리 뒤 재조회 실패: 기존 목록은 그대로 둔다
// 처리 실패 기본 문구(상태 코드별). 호출한 쪽이 넘긴 문구가 같은 코드를 덮어쓴다
const permitAdminFailMessages = {403: permitAdminForbidden, 404: permitAdminGone, 409: permitAlreadyHandled};

// 탭: (서버 status 값, 탭 글자, 빈 목록 안내)
const adminPermitTabs = [
  ('PENDING', '대기', '대기 중인 신청이 없어요'),
  ('APPROVED', '승인', '승인된 신청이 없어요'),
  ('REJECTED', '반려', '반려된 신청이 없어요'),
  ('REVOKED', '취소', '취소된 신청이 없어요'),
];

// 인증 취소(승인된 회원의 인증을 관리자가 거두는 것) 문구
const permitRevokeDone = '인증을 취소했어요';
const permitRevokeConflict = '승인된 신청만 취소할 수 있어요. 이미 취소됐거나 상태가 바뀌었어요';
const permitRevokeBadReason = '취소 사유를 확인해 주세요';
String permitRevokeConfirm(String nickname) => '$nickname 님의 유학생 인증을 취소할까요? 글쓰기·댓글·중고·쪽지를 쓸 수 없게 돼요';
