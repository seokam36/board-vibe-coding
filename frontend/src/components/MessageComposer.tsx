import { useId, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { describeError } from '../errorMessage'
import type { MessageTable } from '../errorMessage'
import Notice from './Notice'
import ReloginNotice from './ReloginNotice'

// 쪽지 길이 한도 (서버 규칙과 같다: 1~1000자). 글자 수는 서버처럼 JS의 length로 센다
const MAX_LENGTH = 1000

// 쪽지 쓰는 칸 하나: 글자 수 표시 + 보내기 버튼 + 실패 안내.
// "판매자에게 쪽지"(상세)와 "답장"(대화 화면)이 똑같이 쓴다. 실제 요청은 send가 하고, 실패하면 던져야 한다
export default function MessageComposer({
  label,
  placeholder,
  submitLabel,
  errorTable,
  fallbackError,
  send,
  onSubmitStart,
}: {
  label: string // textarea의 접근성 이름
  placeholder: string
  submitLabel: string // 평소 버튼 글자 (보내는 중에는 "보내는 중..."으로 바뀐다)
  errorTable: MessageTable // 상태코드별 실패 문구
  fallbackError: string // 표에 없는 실패의 기본 문구
  send: (content: string) => Promise<void>
  // 보내기를 누른 순간(요청을 보내기 직전) 부모에게 알린다. 부모는 이걸 보고 "로그인이 풀려도 이 칸을 없애지 않는다"고 기억한다
  onSubmitStart?: () => void
}) {
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false) // 보내는 중이면 버튼을 잠가 두 번 눌림을 막는다
  const [error, setError] = useState<string | null>(null)
  const [needLogin, setNeedLogin] = useState(false) // 401(재발급 실패): 로그인 안내
  const countId = useId()
  const textareaRef = useRef<HTMLTextAreaElement>(null) // 보낸 뒤 다시 바로 쓸 수 있게 포커스를 돌려줄 때 쓴다

  // 비었거나 공백뿐이면 보낼 수 없다 (서버도 400)
  const empty = text.trim() === ''

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (empty || busy) return
    onSubmitStart?.()
    setBusy(true)
    setError(null)
    setNeedLogin(false)
    try {
      await send(text)
      setText('') // 성공하면 입력칸을 비운다 (상세에서는 이 뒤에 화면이 바뀐다)
      textareaRef.current?.focus() // 키보드로 쓰는 사람이 이어서 쓸 수 있게 (보내는 동안 잠긴 버튼에서 포커스를 잃기 때문)
    } catch (err: unknown) {
      const info = describeError(err, errorTable, fallbackError)
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setError(info.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="message-form" onSubmit={handleSubmit}>
      <textarea
        ref={textareaRef}
        aria-label={label}
        aria-describedby={countId}
        value={text}
        onChange={(e) => setText(e.target.value)}
        rows={3}
        maxLength={MAX_LENGTH}
        placeholder={placeholder}
        className="full-width"
      />
      <div className="message-form-foot">
        <span id={countId} className="message-count">
          {text.length} / {MAX_LENGTH}
        </span>
        <button type="submit" className="btn btn-primary" disabled={empty || busy}>
          {busy ? '보내는 중...' : submitLabel}
        </button>
      </div>
      {/* 실패 안내는 입력칸 아래에 둔다 (쓴 내용은 그대로 남는다) */}
      {error && <Notice kind="error">{error}</Notice>}
      {needLogin && <ReloginNotice />}
    </form>
  )
}
