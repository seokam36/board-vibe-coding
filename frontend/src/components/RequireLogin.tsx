import type { ReactNode } from 'react'
import { Navigate } from 'react-router'
import { useAuth } from '../auth/useAuth'

// 로그인한 사람만 볼 수 있는 화면을 감싸는 문지기. 비로그인이면 /login 으로 보낸다
export default function RequireLogin({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth()
  if (loading) return <p>불러오는 중...</p> // 로그인 여부를 아직 모르면 판단 보류
  if (!user) return <Navigate to="/login" replace />
  return children
}
