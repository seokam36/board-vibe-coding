import { createContext, useContext } from 'react'
import type { VisaPlan } from '../types'

// 보관소 상태: idle = 대상 아님(비로그인·미인증), loading = 모름(불러오는 중), ready = 일정 있음/없음, failed = 불러오기 실패
export type VisaPlanStatus = 'idle' | 'loading' | 'ready' | 'failed'

export interface VisaPlanContextValue {
  status: VisaPlanStatus
  plan: VisaPlan | null // ready일 때만 값이 있다 (일정 없음도 expiryDate가 null인 객체)
  errorMessage: string | null // failed일 때 화면에 보여 줄 문구 (401이면 null)
  needLogin: boolean // failed 이유가 401(재발급까지 실패)인지
  loadedDay: string | null // 이 일정을 받은 서울 날짜(yyyy-MM-dd). 자정을 넘겼는지 알아보는 데 쓴다
  setPlan: (plan: VisaPlan) => void // 저장·삭제 성공 뒤 화면 쪽에서 보관소를 바로 갱신
  reload: () => void // 다시 불러오기 (실패 상태에서 /visa-plan에 들어왔을 때)
}

export const VisaPlanContext = createContext<VisaPlanContextValue | null>(null)

// 비자 일정 보관소를 꺼내 쓰는 훅
export function useVisaPlan(): VisaPlanContextValue {
  const value = useContext(VisaPlanContext)
  if (!value) throw new Error('useVisaPlan은 VisaPlanProvider 안에서만 쓸 수 있습니다')
  return value
}
