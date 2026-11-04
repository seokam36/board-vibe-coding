import { useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router'
import api, { setAuthCallbacks } from '../api'
import { AuthContext } from './useAuth'
import type { AuthUser } from './useAuth'

// 앱 전체를 감싸서 로그인 정보(user)와 login/logout 함수를 나눠 주는 컴포넌트
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)
  // 로그인이 풀린 순간 있던 화면의 경로. null이면 "풀린 적 없음".
  const [expiredPath, setExpiredPath] = useState<string | null>(null)
  // 콜백 안에서 "지금 로그인해 있었는지"와 "지금 어느 화면인지"를 알기 위한 최신값 보관소
  const userRef = useRef<AuthUser | null>(null)
  const location = useLocation() // 현재 주소 (AuthProvider는 BrowserRouter 안쪽이라 쓸 수 있다)
  const locationRef = useRef(location)

  useEffect(() => {
    userRef.current = user
    locationRef.current = location
  }, [user, location])

  // 만료된 화면을 떠나면 표시를 지운다. 안 지우면 나중에 같은 주소로 돌아왔을 때 비로그인인데도 문지기가 통과시킨다.
  // (다른 화면은 주소가 달라서 RequireLogin이 이미 /login 으로 보내므로, 여기서 지우는 건 "돌아왔을 때"만 막으면 된다)
  // effect 대신 "그리는 중에 주소가 바뀐 걸 알아채면 바로 고치기"를 쓴다 (React 권장 방식, 화면이 한 번 덜 그려진다)
  const [prevPath, setPrevPath] = useState(location.pathname)
  if (location.pathname !== prevPath) {
    setPrevPath(location.pathname)
    if (expiredPath && location.pathname !== expiredPath) setExpiredPath(null)
  }

  // 인터셉터(api.ts)의 재발급 결과를 받아 로그인 상태에 반영한다
  useEffect(() => {
    setAuthCallbacks({
      // 재발급 성공: 서버가 돌려준 최신 사용자 정보로 갱신
      onRefreshed: (u) => setUser(u),
      // 재발급 실패: 로그인 상태를 비운다. 로그인해 있던 사람이면 "풀린 그 화면의 경로"를 남겨
      // 그 화면만 /login 으로 튕기지 않고 안내 문구를 보여 주게 한다. (다른 화면으로 가면 바로 /login)
      // 시작 때 /auth/me 가 401인 손님은 원래 비로그인이므로 표시하지 않는다.
      onExpired: () => {
        if (userRef.current) setExpiredPath(locationRef.current.pathname)
        setUser(null)
      },
    })
    return () => setAuthCallbacks(null)
  }, [])

  // 앱 시작 시 한 번: 먼저 /csrf 로 CSRF 쿠키를 받고, 그다음 /auth/me 로 로그인 여부를 확인한다.
  // (쓰기 요청 전에 쿠키가 있어야 하므로 loading이 끝나기 전에는 화면을 그리지 않는다 — App.tsx)
  useEffect(() => {
    let ignore = false
    async function init() {
      try {
        await api.get('/csrf')
        const res = await api.get<AuthUser>('/auth/me')
        if (!ignore) setUser(res.data)
      } catch {
        // /auth/me 가 401이면 그냥 비로그인. 네트워크 오류도 일단 비로그인으로 둔다
        // (access 만료라면 api.ts 인터셉터가 먼저 재발급을 시도한 뒤에 여기로 온다)
        if (!ignore) setUser(null)
      } finally {
        if (!ignore) setLoading(false)
      }
    }
    init()
    return () => {
      ignore = true
    }
  }, [])

  // 로그인: 실패하면 에러가 그대로 던져져서 로그인 화면이 상태코드별 문구를 보여준다
  async function login(username: string, password: string) {
    const res = await api.post<AuthUser>('/auth/login', { username, password })
    setExpiredPath(null) // 다시 로그인했으니 만료 표시 초기화
    setUser(res.data)
  }

  // 로그아웃: 서버에서 쿠키를 지운 뒤 화면의 로그인 상태도 비운다
  async function logout() {
    await api.post('/auth/logout')
    setUser(null)
  }

  return <AuthContext.Provider value={{ user, loading, expiredPath, login, logout }}>{children}</AuthContext.Provider>
}
