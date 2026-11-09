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
