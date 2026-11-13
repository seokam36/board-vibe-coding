import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useLocation } from 'react-router'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import type { UnreadCountResponse } from '../types'
import { UnreadContext } from './unreadContext'

// 안 읽은 쪽지 수를 앱 전체에서 한 번만 들고 있다가 상단바에 나눠 주는 보관소
// 로그인했을 때만 GET을 부른다 (비로그인 401이 토큰 재발급을 돌리지 않게). 로그아웃하면 0으로 되돌린다
export function UnreadProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const userId = user?.id ?? null
  // 숫자가 누구 것인지도 함께 저장한다: 회원이 바뀌면 이전 회원의 숫자가 잠깐이라도 보이지 않게
  const [stored, setStored] = useState<{ userId: number | null; count: number }>({ userId, count: 0 })
  if (stored.userId !== userId) setStored({ userId, count: 0 }) // 로그인·로그아웃·회원 교체 때 렌더 중에 바로 비운다
  const userIdRef = useRef<number | null>(userId) // refresh가 항상 "지금 회원"을 보게 (refresh 함수 자체는 안 바뀌게)
  const seq = useRef(0) // 요청 번호: 여러 번 겹쳐 불러도 가장 마지막에 보낸 응답만 반영한다

  const refresh = useCallback(() => {
    const id = userIdRef.current
    if (id === null) return // 로그인 안 했으면 부르지 않는다
    const mine = ++seq.current
    api
      .get<UnreadCountResponse>('/conversations/unread-count')
      .then((res) => {
        if (mine !== seq.current) return // 더 새 요청이 있으니 이 응답은 버린다
        setStored((s) => (s.userId === id ? { ...s, count: res.data.count } : s))
      })
      .catch(() => {
        // 숫자는 부가 표시라 실패해도 조용히 둔다 (이전 숫자 유지)
      })
  }, [])

  // 회원이 바뀔 때(로그인 직후 포함)와 화면(주소)이 바뀔 때 받아 온다 (S14 결정: 화면 이동 때마다 다시 센다, 실시간은 아님)
  // 로그아웃이면 진행 중이던 응답을 버린다. 요청이 겹쳐도 seq가 가장 마지막 것만 반영한다
  const { pathname } = useLocation()
  useEffect(() => {
    userIdRef.current = userId
    seq.current++
    refresh()
  }, [userId, pathname, refresh])

  const value = useMemo(() => ({ count: stored.count, refresh }), [stored.count, refresh])
  return <UnreadContext.Provider value={value}>{children}</UnreadContext.Provider>
}
