import axios from 'axios'

// axios 에러에 response가 없으면 서버 꺼짐/CORS 차단 등으로 서버와 통신 자체가 안 된 경우
export const NETWORK_ERROR_MESSAGE = '서버에 연결할 수 없습니다'

// 에러에서 HTTP 상태코드를 꺼낸다. 응답이 없거나 axios 에러가 아니면 undefined
export function getStatus(error: unknown): number | undefined {
  return axios.isAxiosError(error) ? error.response?.status : undefined
}

// 응답이 없는 네트워크 오류인지 확인
export function isNetworkError(error: unknown): boolean {
  return axios.isAxiosError(error) && !error.response
}

// 보통 앱 시작 때 CSRF 쿠키(/csrf)를 못 받았을 때 403에 쓰는 공용 문구
export const CSRF_MESSAGE = '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요'

// 쓰기 요청이 401을 받았을 때(자동 재발급까지 실패해 로그인이 풀린 경우) 보여 주는 안내. 옆에 로그인 링크를 같이 둔다
export const RELOGIN_MESSAGE = '다시 로그인해 주세요'

// 상태코드별 문구 표. 예: { 404: '글을 찾을 수 없습니다', 400: '입력을 확인해 주세요' }
export type MessageTable = Partial<Record<number, string>>

// describeError의 결과: 로그인이 풀려 안내가 필요한 경우 / 보여 줄 문구(status는 409·404처럼 상태 변경이 붙는 분기용)
export type ErrorInfo = { kind: 'needLogin' } | { kind: 'message'; message: string; status: number | undefined }

// 에러를 화면에 보여 줄 형태로 바꾼다. 분기 순서는 항상 같다:
// 네트워크 오류 → 공용 문구 / (표에 없는) 401 → needLogin / 표에 있는 코드 → 그 문구 / 나머지 → 기본 문구
// 표에 401을 직접 적으면 needLogin 대신 그 문구를 쓴다 (로그인 화면의 "아이디 또는 비밀번호가 틀렸습니다"처럼 401이 다른 뜻일 때)
export function describeError(error: unknown, table: MessageTable, fallback: string): ErrorInfo {
  if (isNetworkError(error)) return { kind: 'message', message: NETWORK_ERROR_MESSAGE, status: undefined }
  const status = getStatus(error)
  if (status === 401 && table[401] === undefined) return { kind: 'needLogin' }
  return { kind: 'message', message: (status !== undefined && table[status]) || fallback, status }
}

// 글자만 필요한 곳(불러오기 실패 등, 로그인 안내가 따로 없는 곳)용. 401도 표에 없으면 기본 문구
export function errorText(error: unknown, table: MessageTable, fallback: string): string {
  const info = describeError(error, table, fallback)
  return info.kind === 'message' ? info.message : fallback
}

// 화면 여러 곳에서 겹쳐 쓰는 에러 문구 사전. 이름은 "무엇을 못 했는지"로 짓는다.
// 글자(띄어쓰기·마침표 포함)를 바꾸면 화면 문구가 바뀌니 errorMessage.test.ts가 그대로인지 지켜 준다.
export const ERROR_MESSAGES = {
  // --- 글 ---
  // 글이 없을 때(404). 상세·수정 불러오기는 id가 이상한 400에도 같은 문구. 공감·댓글 등록의 404에도 씀
  POST_NOT_FOUND: '글을 찾을 수 없습니다',
  // 글 상세·수정 화면에서 글을 불러오다 실패했을 때의 기본 문구
  POST_LOAD_FAILED: '글을 불러오지 못했습니다',
  // 글 작성·수정에서 제목/내용/말머리 입력이 잘못됐을 때(400)
  POST_FIELDS_INVALID: '제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)',
  // 글 작성·수정 저장 실패 기본 문구
  SAVE_FAILED: '저장하지 못했습니다',

  // --- 학교 ---
  // 글 목록의 학교 필터가 없는 학교일 때(404)
  SCHOOL_NOT_FOUND: '존재하지 않는 학교입니다',
  // 댓글·공감·신고 403: 학교 인증을 안 했거나 다른 학교의 글일 때
  SCHOOL_GATE_POST_ACTION:
    '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요',

  // --- 삭제 ---
  // 삭제 403인데 내 것(권한이 있어야 하는데 거부됨)일 때: 새로고침 안내
  DELETE_RETRY_AFTER_RELOAD: '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요',
  // 삭제 403인데 남의 것일 때
  DELETE_FORBIDDEN: '삭제할 권한이 없습니다',
  // 삭제 실패 기본 문구
  DELETE_FAILED: '삭제하지 못했습니다',

  // --- 댓글 · 공감 · 신고 ---
  // 공감 버튼: 이미 공감한 글(409 응답, 그리고 화면에서 직접 막을 때)
  POST_ALREADY_LIKED: '이미 공감한 글입니다',
  // "처리" 동작의 기본 실패 문구 (공감 버튼, 관리자 신고 처리)
  ACTION_FAILED: '처리하지 못했습니다',
  // 이미 지워진 글/댓글 404 (글 삭제, 댓글 수정·삭제, 신고, 관리자 삭제). label은 '글' 또는 '댓글'
  alreadyDeleted: (label: string) => `이미 삭제된 ${label}입니다`,

  // --- 비자 후기 ---
  // 후기가 없을 때(404). 상세·수정 불러오기는 id가 이상한 400에도, 수정 저장 404에도 같은 문구
  REVIEW_NOT_FOUND: '후기를 찾을 수 없습니다',
  // 후기 상세·수정 화면에서 후기를 불러오다 실패했을 때의 기본 문구
  REVIEW_LOAD_FAILED: '후기를 불러오지 못했습니다',
  // 후기 작성·수정에서 접수일/결과일/본문이 잘못됐을 때(400)
  REVIEW_FIELDS_INVALID: '날짜를 확인해 주세요 (접수일은 결과일과 같거나 앞서야 하고, 둘 다 오늘까지. 본문은 2000자까지)',
  // 후기 목록·통계의 학교/비자 종류/신청 종류 조건이 잘못됐을 때(400)
  REVIEW_FILTER_INVALID: '잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요',
  // 후기 목록·통계·작성 폼에서 선택지(학교·비자 종류 등) 목록을 못 받았을 때 알림
  // (수정 폼은 "지금 값으로만 저장할 수 있습니다"가 붙은 다른 문구라 화면에 그대로 둔다)
  REVIEW_OPTIONS_LOAD_FAILED: '선택지 목록을 불러오지 못했습니다',
  // 후기 작성·비자 일정 저장에서 학교 인증이 없거나 CSRF가 어긋났을 때(403)
  VISA_SCHOOL_REQUIRED_RETRY: '학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
} as const
