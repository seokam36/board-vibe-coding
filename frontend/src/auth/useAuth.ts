import { createContext, useContext } from 'react'

// /auth/login, /auth/me 가 돌려주는 로그인 사용자
export interface AuthUser {
  id: number
  username: string
  nickname: string // 화면에 보여 주는 이름 (S5-a). 글쓴이·상단바에는 닉네임만, 아이디(username)는 내 정보 화면에서만 보여 준다
  verifiedSchool: { id: number; name: string } | null // 학교 인증을 마친 학교 (S10). null이면 미인증 → 읽기만 가능
  role: 'USER' | 'ADMIN' // 회원 역할 (S9). ADMIN이면 남의 글·댓글 삭제와 신고 관리 화면을 쓸 수 있다. 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버 검사
}

export interface AuthContextValue {
  user: AuthUser | null // null이면 비로그인
  loading: boolean // 시작할 때 /csrf 와 /auth/me 확인이 끝났는지
  expiredPath: string | null // 로그인이 풀린 그 순간 있던 화면의 경로 (그 화면에서만 /login 으로 튕기지 않으려고 쓴다)
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
  updateUser: (user: AuthUser) => void // 서버가 돌려준 새 사용자 정보로 교체 (닉네임 변경 직후 상단바 갱신용)
  clearUser: () => void // 서버 요청 없이 화면의 로그인 상태만 비움 (서버가 이미 쿠키를 지운 비밀번호 변경·탈퇴 뒤에 쓴다)
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
