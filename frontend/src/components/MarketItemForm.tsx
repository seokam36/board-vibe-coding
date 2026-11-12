import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router'
import { describeError, ERROR_MESSAGES } from '../errorMessage'
import type { MessageTable } from '../errorMessage'
import Notice from './Notice'
import ReloginNotice from './ReloginNotice'
import type { MarketItemRequest } from '../types'

const MAX_PRICE = 10_000_000

interface Props {
  initial?: { title: string; content: string; tradeType: 'SALE' | 'FREE'; price: number } // 수정 화면에서 채울 처음 값
  // 저장 요청을 보내는 함수 (성공하면 이 함수가 화면을 이동시킨다). 실패하면 throw
  onSave: (body: MarketItemRequest) => Promise<void>
  errorTable: MessageTable // 저장 실패 상태코드별 문구
  cancelTo: string
  locked?: boolean // 다른 작업(사진 올리기·지우기) 중이면 [저장]을 잠근다
  onSavingChange?: (saving: boolean) => void // 글자 저장 중인지 부모에게 알린다 (수정 화면의 사진 영역 잠금용)
}

// 중고·나눔 글 올리기·수정 공용 폼 (요청 DTO가 하나라 폼도 하나)
export default function MarketItemForm({ initial, onSave, errorTable, cancelTo, locked = false, onSavingChange }: Props) {
  // 입력값은 state로 관리한다. 실패해도 그대로 남는다
  const [title, setTitle] = useState(initial?.title ?? '')
  const [content, setContent] = useState(initial?.content ?? '')
  const [tradeType, setTradeType] = useState<'SALE' | 'FREE'>(initial?.tradeType ?? 'SALE')
  // 가격은 입력 중에 빈 칸·글자가 있을 수 있어 문자열로 들고 있다가 보낼 때 숫자로 바꾼다
  const [price, setPrice] = useState(initial && initial.tradeType === 'SALE' ? String(initial.price) : '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 저장 중 401(재발급까지 실패): 로그인 안내를 따로 보여 준다
  const [needLogin, setNeedLogin] = useState(false)

  // 저장 중인지 바뀔 때마다 부모에게 알린다
  useEffect(() => {
    onSavingChange?.(saving)
  }, [saving, onSavingChange])

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (locked) return // 엔터로 제출되는 경우도 막는다
    setError(null)
    setNeedLogin(false)
    if (!title.trim() || !content.trim()) {
      setError('제목과 본문을 입력해 주세요')
      return
    }
    const body: MarketItemRequest = { title, content, tradeType }
    if (tradeType === 'SALE') {
      // 판매: 가격 1~10,000,000 정수 필수. 어긋나면 서버에 보내지 않고 화면에 머문다
      const n = Number(price)
      if (!/^\d+$/.test(price.trim()) || n < 1 || n > MAX_PRICE) {
        setError('판매 가격은 1원 이상 10,000,000원 이하의 숫자로 입력해 주세요')
        return
      }
      body.price = n
    } // 나눔이면 price를 보내지 않는다 (서버가 0으로 저장)
    setSaving(true)
    try {
      await onSave(body)
    } catch (err: unknown) {
      const info = describeError(err, errorTable, ERROR_MESSAGES.SAVE_FAILED)
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setError(info.message)
      setSaving(false) // 실패했을 때만 버튼을 되살린다 (성공하면 페이지가 바뀐다)
    }
  }

  return (
    <form className="panel" onSubmit={handleSubmit}>
      <div className="field">
        <label>
          제목
          <input
            type="text"
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={100}
            className="full-width"
          />
        </label>
      </div>
      <div className="filters">
        <select aria-label="판매/나눔" value={tradeType} onChange={(e) => setTradeType(e.target.value as 'SALE' | 'FREE')}>
          <option value="SALE">판매</option>
          <option value="FREE">나눔</option>
        </select>
        {/* 나눔이면 가격 칸을 숨긴다 */}
        {tradeType === 'SALE' && (
          <label>
            가격(원){' '}
            <input type="text" inputMode="numeric" value={price} onChange={(e) => setPrice(e.target.value)} />
          </label>
        )}
      </div>
      <div className="field">
        <label>
          본문
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            maxLength={2000}
            rows={8}
            className="full-width"
          />
        </label>
        <span className="hint meta">{content.length} / 2000</span>
      </div>
      {error && <Notice kind="error">{error}</Notice>}
      {needLogin && <ReloginNotice />}
      <div className="actions">
        <button type="submit" className="btn btn-primary" disabled={saving || locked}>
          {saving ? '저장 중...' : '저장'}
        </button>
        {/* 저장 중에는 링크를 못 막으니 비활성 버튼 모양으로 바꾼다 (상세 화면의 [수정]과 같은 방식) */}
        {saving ? (
          <button type="button" className="btn" disabled>
            취소
          </button>
        ) : (
          <Link to={cancelTo} className="btn">
            취소
          </Link>
        )}
      </div>
    </form>
  )
}
