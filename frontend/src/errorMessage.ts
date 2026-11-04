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

// 쓰기 요청이 401을 받았을 때(자동 재발급까지 실패해 로그인이 풀린 경우) 보여 주는 안내. 옆에 로그인 링크를 같이 둔다
export const RELOGIN_MESSAGE = '다시 로그인해 주세요'
