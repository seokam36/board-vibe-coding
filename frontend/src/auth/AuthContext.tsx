import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import api from '../api'
import { AuthContext } from './useAuth'
import type { AuthUser } from './useAuth'

// 앱 전체를 감싸서 로그인 정보(user)와 login/logout 함수를 나눠 주는 컴포넌트
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null)
  const [loading, setLoading] = useState(true)

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
        // (재발급 인터셉터는 S4-e-2에서)
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
    setUser(res.data)
  }

  // 로그아웃: 서버에서 쿠키를 지운 뒤 화면의 로그인 상태도 비운다
  async function logout() {
    await api.post('/auth/logout')
    setUser(null)
  }

  return <AuthContext.Provider value={{ user, loading, login, logout }}>{children}</AuthContext.Provider>
}
