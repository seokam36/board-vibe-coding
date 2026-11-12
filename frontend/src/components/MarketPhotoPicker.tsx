import { useRef, useState } from 'react'
import type { ChangeEvent } from 'react'
import { ERROR_MESSAGES } from '../errorMessage'
import { checkPhotoFiles, MAX_PHOTOS } from '../marketPhotoUpload'
import Notice from './Notice'
import ReloginNotice from './ReloginNotice'

export interface PhotoItem {
  key: number | string
  src: string // 썸네일로 보여 줄 주소 (수정 화면: 서버 썸네일, 글쓰기 화면: 미리보기 blob 주소)
}

interface Props {
  items: PhotoItem[]
  disabled: boolean // 올리는 중·지우는 중·저장 중이면 추가·삭제 버튼을 잠근다
  progress: string | null // "사진 올리는 중 2/5" (없으면 null)
  error: string | null // 서버 요청이 실패한 문구
  needLogin: boolean // 요청 중 401: 로그인 안내
  removeLabel: '삭제' | '빼기' // 수정 화면은 서버에서 지우니 "삭제", 글쓰기 화면은 미리보기만 빼니 "빼기"
  onAdd: (files: File[]) => void // 브라우저 검사를 통과한 파일만 넘어온다
  onRemove: (key: number | string) => void
}

// 글쓰기·수정 화면 공용 사진 영역. 목록 그리기 + 파일 고르기 + 브라우저 사전 검사까지만 하고,
// 실제로 올리고 지우는 일은 화면(부모)이 한다
export default function MarketPhotoPicker({ items, disabled, progress, error, needLogin, removeLabel, onAdd, onRemove }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [checkMessage, setCheckMessage] = useState<string | null>(null) // 사전 검사에서 뺀 이유
  const full = items.length >= MAX_PHOTOS

  function handleChange(e: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(e.target.files ?? [])
    e.target.value = '' // 같은 파일을 다시 골라도 change가 일어나게 비운다
    if (files.length === 0 || disabled) return // 올리는 중이면 겹쳐 실행하지 않는다
    const { accepted, message } = checkPhotoFiles(files, items.length)
    setCheckMessage(message)
    if (accepted.length > 0) onAdd(accepted)
  }

  return (
    <section className="panel market-photo-picker" aria-label="사진">
      <h2 className="market-photo-picker-title">
        사진 ({items.length}/{MAX_PHOTOS})
      </h2>
      {items.length > 0 && (
        <ul className="market-photo-list">
          {items.map((it, i) => (
            <li key={it.key} className="market-photo-item">
              <img className="market-photo-preview" src={it.src} alt={`사진 ${i + 1}`} />
              <button
                type="button"
                className="btn btn-sm"
                aria-label={`사진 ${i + 1} ${removeLabel}`}
                disabled={disabled}
                onClick={() => onRemove(it.key)}
              >
                {removeLabel}
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="actions">
        <button type="button" className="btn" disabled={disabled || full} onClick={() => inputRef.current?.click()}>
          사진 추가
        </button>
        {/* 실제 파일 입력은 눈에 안 보이게 두고 위 버튼이 대신 연다 (버튼은 키보드로 누를 수 있다) */}
        <input
          ref={inputRef}
          type="file"
          className="sr-only"
          tabIndex={-1}
          aria-label="사진 파일 선택"
          accept="image/jpeg,image/png"
          multiple
          disabled={disabled || full}
          onChange={handleChange}
        />
        {full && <span className="hint meta">{ERROR_MESSAGES.MARKET_PHOTO_MAX_COUNT}</span>}
      </div>
      {progress && <Notice kind="info">{progress}</Notice>}
      {checkMessage && <Notice kind="error">{checkMessage}</Notice>}
      {error && <Notice kind="error">{error}</Notice>}
      {needLogin && <ReloginNotice />}
    </section>
  )
}
