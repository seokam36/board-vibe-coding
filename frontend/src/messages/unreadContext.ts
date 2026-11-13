import { createContext, useContext } from 'react'

export interface UnreadContextValue {
  count: number // 안 읽은 쪽지 수. 비로그인이거나 아직 못 받았으면 0
  refresh: () => void // 서버에서 다시 받아 오기 (대화를 읽음 처리한 뒤에 부른다). 실패하면 숫자를 그대로 둔다
}

// 상단바(숫자 표시)와 대화 화면(읽음 처리 뒤 숫자 갱신)을 이어 주는 공용 보관함
// (컴포넌트 파일과 분리한 이유: AuthContext와 같다 — 자동 새로고침 안정성)
export const UnreadContext = createContext<UnreadContextValue | null>(null)

// 안 읽은 쪽지 보관함을 꺼내 쓰는 훅
export function useUnread(): UnreadContextValue {
  const value = useContext(UnreadContext)
  if (!value) throw new Error('useUnread는 UnreadProvider 안에서만 쓸 수 있습니다')
  return value
}
