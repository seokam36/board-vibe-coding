import { createContext, useContext } from 'react'

// 확인 창에 넘기는 옵션
export interface ConfirmOptions {
  message: string // 물어볼 문구 (줄바꿈 가능)
  confirmLabel: string // 확인 버튼 글자: 동작 이름 그대로 ("삭제", "탈퇴")
  danger?: boolean // 되돌릴 수 없는 동작이면 true → 확인 버튼이 빨간색
}

// confirm(옵션)을 부르면 창이 뜨고, 확인=true / 취소·Esc=false 로 답이 돌아온다
export type ConfirmFn = (options: ConfirmOptions) => Promise<boolean>

export const ConfirmContext = createContext<ConfirmFn | null>(null)

// 사용법: const confirm = useConfirm();  if (!(await confirm({...}))) return
export function useConfirm(): ConfirmFn {
  const fn = useContext(ConfirmContext)
  if (!fn) throw new Error('useConfirm은 ConfirmProvider 안에서만 쓸 수 있습니다')
  return fn
}
