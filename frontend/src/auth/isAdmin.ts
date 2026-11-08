import type { AuthUser } from './useAuth'

// 관리자인지 판단하는 유일한 곳. 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버 검사
export function isAdmin(user: AuthUser | null | undefined): boolean {
  return user?.role === 'ADMIN'
}
