import axios from 'axios'
import type { InternalAxiosRequestConfig } from 'axios'
import type { AuthUser } from './auth/useAuth'

// 백엔드 호출용 axios 인스턴스. 주소는 여기서만 정한다.
// 기본값 '/api': 같은 출처(화면과 같은 주소)로 보내서, 로컬은 Vite 프록시가, 배포는 nginx가 백엔드로 전달한다.
// 같은 출처라서 withCredentials 없이도 쿠키가 자동으로 오가고,
// axios가 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 자동 첨부한다 (withXSRFToken도 켤 필요 없음).
// 다른 출처로 바꾸면(예: http://localhost:8080) 쿠키·CSRF 헤더가 안 붙으니 주의.
// ?? 대신 || 를 쓰는 이유: ?? 는 null/undefined만 걸러서,
// .env에 `VITE_API_BASE_URL=`처럼 빈 값을 두면 baseURL이 ""가 되어 버린다. ||는 빈 문자열도 기본값으로 바꾼다.
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
})

// ---- 401이면 access 토큰을 자동으로 다시 받고(재발급) 원래 요청을 한 번만 다시 보낸다 ----

// 재발급 결과를 화면(AuthProvider)에 알리는 콜백. 인터셉터는 React 밖에 있어서 상태를 직접 못 바꾼다.
// 그래서 api.ts에 "등록 함수"를 두고 AuthProvider가 useEffect에서 등록·해제한다.
// (대안: AuthProvider 안에서 인터셉터를 직접 등록·eject. 하지만 StrictMode에서 등록·해제가 겹치고
//  시작 요청(/auth/me)보다 늦게 등록될 위험이 있어, 인터셉터는 이 파일에 한 번만 두는 쪽을 골랐다.)
interface AuthCallbacks {
  onRefreshed: (user: AuthUser) => void // 재발급 성공: 최신 사용자 정보
  onExpired: () => void // 재발급 실패(401): 로그인 풀림
}
let callbacks: AuthCallbacks | null = null
export function setAuthCallbacks(cb: AuthCallbacks | null) {
  callbacks = cb
}

// 재발급 대상에서 빼는 주소들. 이 주소의 401은 "재발급으로 해결될 문제"가 아니기 때문이다.
// (refresh 자체가 401이면 무한 반복, login/signup 401은 아이디·비밀번호 오류, logout은 이미 로그아웃 상태)
// /auth/me 는 일부러 뺐다: access가 만료된 채 새로고침해도 로그인이 유지되게 하려고.
const NO_REFRESH_PATHS = ['/auth/refresh', '/auth/login', '/auth/signup', '/auth/logout']
function isNoRefreshUrl(url: string | undefined): boolean {
  // baseURL이 붙어 있든 아니든, 쿼리를 뗀 뒤 끝부분이 같은지로 비교한다
  const path = (url ?? '').split('?')[0]
  return NO_REFRESH_PATHS.some((p) => path.endsWith(p))
}

// single-flight: 진행 중인 재발급은 항상 하나만. 그 사이 401을 받은 요청들은 이 Promise를 같이 기다린다.
// refresh 토큰은 한 번 쓰면 폐기되고, 두 번째 사용은 401과 함께 쿠키를 지워 버리기 때문에 꼭 필요하다.
let refreshing: Promise<void> | null = null
function refreshOnce(): Promise<void> {
  if (!refreshing) {
    refreshing = api
      // timeout 10초: 서버가 대답을 안 하면 refreshing이 영원히 안 비워져 기다리는 요청이 전부 멈추기 때문.
      // 시간이 지나면 응답 없는 에러(ECONNABORTED)가 되어 "네트워크 오류"처럼 다뤄진다.
      .post<AuthUser>('/auth/refresh', null, { timeout: 10000 })
      .then((res) => {
        callbacks?.onRefreshed(res.data)
      })
      .catch((e: unknown) => {
        // 진짜 401(refresh도 만료·폐기)일 때만 로그아웃 처리. 서버 꺼짐 같은 일시 오류는 로그인 상태를 건드리지 않는다
        if (axios.isAxiosError(e) && e.response?.status === 401) callbacks?.onExpired()
        throw e // 어떤 에러였는지는 아래 인터셉터가 보고 판단한다
      })
      .finally(() => {
        refreshing = null // 성공이든 실패든 비워야 다음 만료 때 다시 재발급할 수 있다
      })
  }
  return refreshing
}

// 재시도 표시(_retry)를 달기 위해 config 타입을 넓힌다
type RetryConfig = InternalAxiosRequestConfig & { _retry?: boolean }

api.interceptors.response.use(
  (res) => res, // 성공 응답은 그대로
  async (error: unknown) => {
    if (!axios.isAxiosError(error)) return Promise.reject(error)
    const original = error.config as RetryConfig | undefined
    if (
      error.response?.status !== 401 ||
      !original ||
      original._retry || // 이미 한 번 재시도한 요청의 401은 그대로 실패시킨다 (무한 반복 방지)
      isNoRefreshUrl(original.url)
    ) {
      return Promise.reject(error)
    }
    original._retry = true
    try {
      await refreshOnce()
    } catch (refreshErr: unknown) {
      // 재발급이 401로 거절됨(로그인이 진짜 풀림): 호출한 쪽에는 원래 401을 넘긴다 → 화면이 "다시 로그인" 안내
      if (axios.isAxiosError(refreshErr) && refreshErr.response?.status === 401) {
        return Promise.reject(error)
      }
      // 네트워크 끊김·timeout·5xx·403 등: 로그인이 풀린 게 아니므로 "다시 로그인" 대신 그 에러를 그대로 넘긴다
      return Promise.reject(refreshErr)
    }
    return api(original) // 새 쿠키가 생겼으니 원래 요청을 다시 보낸다
  },
)

export default api
