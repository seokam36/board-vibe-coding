import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode, SyntheticEvent } from 'react'
import { useLocation } from 'react-router'
import { ConfirmContext } from './confirmContext'
import type { ConfirmFn, ConfirmOptions } from './confirmContext'

// 앱 맨 위에 한 번만 두는 확인 창. 브라우저 기본 확인 창 대신 네이티브 <dialog>를 쓴다
// (Esc 닫기, 뒤 화면 클릭 막기, 닫힐 때 원래 버튼으로 포커스 복귀를 브라우저가 해 준다)
export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [options, setOptions] = useState<ConfirmOptions | null>(null)
  const dialogRef = useRef<HTMLDialogElement>(null)
  // 답을 기다리는 Promise의 resolve 함수
  const resolverRef = useRef<((ok: boolean) => void) | null>(null)

  const confirm: ConfirmFn = useCallback((opts) => {
    // 혹시 이미 떠 있으면 앞의 것은 취소로 끝낸다
    resolverRef.current?.(false)
    return new Promise<boolean>((resolve) => {
      resolverRef.current = resolve
      setOptions(opts)
    })
  }, [])

  // 옵션이 생기면 모달로 연다 (returnValue를 비워 이전 답이 남지 않게 한다)
  useEffect(() => {
    const dialog = dialogRef.current
    if (options && dialog && !dialog.open) {
      dialog.returnValue = ''
      dialog.showModal()
    }
  }, [options])

  // 창이 열린 채 화면이 바뀌면(브라우저 뒤로가기 등) 취소로 닫는다.
  // 안 닫으면 떠난 화면의 "삭제"가 다른 화면 위에 남아 눌릴 수 있다(기본 확인 창은 막혀서 이런 일이 없었다)
  const { key: locationKey } = useLocation()
  useEffect(() => {
    if (dialogRef.current?.open) dialogRef.current.close() // close 이벤트 → handleClose가 false로 끝낸다
  }, [locationKey])

  // 닫힐 때(버튼·Esc 모두): 확인 버튼으로 닫혔을 때만 true
  function handleClose(e: SyntheticEvent<HTMLDialogElement>) {
    resolverRef.current?.(e.currentTarget.returnValue === 'ok')
    resolverRef.current = null
    setOptions(null)
  }

  return (
    <ConfirmContext.Provider value={confirm}>
      {children}
      <dialog
        ref={dialogRef}
        className="confirm-dialog"
        aria-labelledby="confirm-title"
        aria-describedby="confirm-message"
        onClose={handleClose}
      >
        {options && (
          // method="dialog": 버튼을 누르면 dialog가 닫히고 버튼의 value가 returnValue가 된다
          <form method="dialog">
            <h2 id="confirm-title" className="confirm-title">확인</h2>
            <p id="confirm-message" className="confirm-message">{options.message}</p>
            <div className="confirm-actions">
              {/* 취소를 먼저 두면 열릴 때 포커스가 여기로 와서 실수로 확인을 누르기 어렵다 */}
              <button type="submit" value="cancel" className="btn" autoFocus>취소</button>
              <button type="submit" value="ok" className={options.danger ? 'btn btn-danger' : 'btn btn-primary'}>
                {options.confirmLabel}
              </button>
            </div>
          </form>
        )}
      </dialog>
    </ConfirmContext.Provider>
  )
}
