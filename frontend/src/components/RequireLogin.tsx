import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { useAuth } from '../auth/useAuth'

// 로그인한 사람만 볼 수 있는 화면을 감싸는 문지기. 비로그인이면 /login 으로 보낸다
// (예외: 로그인이 풀린 바로 그 화면에서는 입력 내용을 지키려고 그대로 둔다)
export default function RequireLogin({ children }: { children: ReactNode }) {
  const { user, loading, expiredPath } = useAuth()
  const location = useLocation()
  if (loading) return <p>불러오는 중...</p> // 로그인 여부를 아직 모르면 판단 보류
  // 로그인해 있다가 재발급 실패로 풀린 경우: 입력 중인 내용이 사라지지 않게, "풀린 그 화면"에서만 /login 으로 튕기지 않는다.
  // (화면 안에서 요청이 401을 받으면 "다시 로그인해 주세요" 문구와 로그인 링크를 보여 준다)
  // 다른 경로로 들어오면 expiredPath와 달라 바로 /login 으로 간다 (만료 표시가 영원히 남아 문이 열려 있는 것을 막는다)
  if (!user && expiredPath !== location.pathname) return <Navigate to="/login" replace />
  return children
}
