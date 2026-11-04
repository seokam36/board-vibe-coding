import { createContext, useContext } from 'react'

// /auth/login, /auth/me 가 돌려주는 로그인 사용자
export interface AuthUser {
  id: number
  username: string
}

export interface AuthContextValue {
  user: AuthUser | null // null이면 비로그인
  loading: boolean // 시작할 때 /csrf 와 /auth/me 확인이 끝났는지
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

// Context: props를 일일이 넘기지 않고 앱 어디서든 로그인 정보를 꺼내 쓰게 해 주는 "공용 보관함"
// (컴포넌트 파일과 분리한 이유: 한 파일에 컴포넌트와 함수를 같이 내보내면 개발 중 자동 새로고침이 불안정해진다)
export const AuthContext = createContext<AuthContextValue | null>(null)

// 컴포넌트에서 로그인 정보를 꺼내 쓰는 훅
export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth는 AuthProvider 안에서만 쓸 수 있습니다')
  return value
}
