import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import { describeError } from '../errorMessage'
import { seoulToday } from '../formatDate'
import type { VisaPlan } from '../types'
import { VisaPlanContext } from './visaPlanContext'
import type { VisaPlanStatus } from './visaPlanContext'

interface Stored {
  userId: number | null // 이 상태가 누구 것인지 (회원이 바뀌면 비운다)
  status: VisaPlanStatus
  plan: VisaPlan | null
  errorMessage: string | null
  needLogin: boolean
  loadedDay: string | null // GET 응답을 받은 서울 날짜
}

const blank = (userId: number | null): Stored => ({
  userId,
  status: userId === null ? 'idle' : 'loading',
  plan: null,
  errorMessage: null,
  needLogin: false,
  loadedDay: null,
})

// 내 비자 일정을 앱 전체에서 한 번만 불러와 나눠 주는 보관소 (상단 띠와 /visa-plan 화면이 같이 쓴다)
// 학교 인증 회원일 때만 GET을 부른다. 화면 이동마다 다시 부르지 않는다
export function VisaPlanProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth()
  const userId = user?.verifiedSchool ? user.id : null
  const [state, setState] = useState<Stored>(() => blank(userId))
  const inFlight = useRef<number | null>(null) // 지금 요청 중인 회원 (같은 요청을 두 번 보내지 않으려고)

  // 회원이 바뀌었거나(로그인·로그아웃·인증 완료) 대상에서 빠지면 렌더 중에 바로 비운다 (이미 쓰는 패턴)
  if (state.userId !== userId) setState(blank(userId))

  const load = useCallback((id: number) => {
    if (inFlight.current === id) return
    inFlight.current = id
    // 답이 늦게 와도 그사이 회원이 바뀌었으면 버린다 (state.userId가 다르면 그대로 둠)
    const apply = (patch: Partial<Stored>) => setState((s) => (s.userId === id ? { ...s, ...patch } : s))
    // 상태를 바꾸기 전에 "요청 중" 표시부터 지운다 (그 사이 reload가 와도 막히지 않게)
    const done = () => {
      if (inFlight.current === id) inFlight.current = null
    }
    api
      .get<VisaPlan>('/users/me/visa-plan')
      .then((res) => {
        done()
        apply({ status: 'ready', plan: res.data, loadedDay: seoulToday() })
      })
      .catch((e: unknown) => {
        done()
        const info = describeError(e, {}, '일정을 불러오지 못했습니다')
        if (info.kind === 'needLogin') apply({ status: 'failed', needLogin: true })
        else apply({ status: 'failed', errorMessage: info.message })
      })
  }, [])

  useEffect(() => {
    if (userId !== null) load(userId)
  }, [userId, load])

  const setPlan = useCallback((plan: VisaPlan) => {
    setState((s) => (s.userId === null ? s : { ...s, status: 'ready', plan, errorMessage: null, needLogin: false, loadedDay: seoulToday() }))
  }, [])

  const reload = useCallback(() => {
    if (userId === null) return
    setState((s) => (s.userId === userId ? { ...s, status: 'loading', errorMessage: null, needLogin: false } : s))
    load(userId)
  }, [userId, load])

  return (
    <VisaPlanContext.Provider
      value={{ status: state.status, plan: state.plan, errorMessage: state.errorMessage, needLogin: state.needLogin, loadedDay: state.loadedDay, setPlan, reload }}
    >
      {children}
    </VisaPlanContext.Provider>
  )
}
