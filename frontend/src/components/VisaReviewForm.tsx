import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router'
import { describeError } from '../errorMessage'
import type { MessageTable } from '../errorMessage'
import Notice from './Notice'
import ReloginNotice from './ReloginNotice'
import type { VisaOption, VisaReviewOptions, VisaReviewRequest } from '../types'

// 서울 기준 오늘(yyyy-MM-dd). 브라우저 시간대가 아니라 Asia/Seoul로 계산한다 (en-CA 형식이 yyyy-MM-dd)
function seoulToday(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Seoul' }).format(new Date())
}

// 받은 선택지 + (목록에 없거나 못 받았으면) 후기가 원래 가진 값. 글 수정의 말머리와 같은 패턴
function withCurrent(list: VisaOption[] | undefined, current: VisaOption | undefined): VisaOption[] {
  const out = [...(list ?? [])]
  if (current && !out.some((o) => o.code === current.code)) out.unshift(current)
  return out
}

interface Props {
  schoolName: string // 고정 뱃지에 보일 학교 (바꿀 수 없다)
  options: VisaReviewOptions | null // 못 받았으면 null
  optionsFailed: boolean
  // 수정 화면에서 채울 처음 값과, 선택지를 못 받아도 쓸 수 있게 끼워 넣을 현재 값
  initial?: VisaReviewRequest
  current?: { visaType: VisaOption; applicationType: VisaOption; result: VisaOption }
  // 저장 요청을 보내는 함수 (성공하면 이 함수가 화면을 이동시킨다). 실패하면 throw
  onSave: (body: VisaReviewRequest) => Promise<void>
  errorTable: MessageTable // 저장 실패 상태코드별 문구
  cancelTo: string
  cancelLabel: string
}

// 비자 후기 작성·수정 공용 폼 (요청 DTO가 하나라 폼도 하나)
export default function VisaReviewForm({
  schoolName,
  options,
  optionsFailed,
  initial,
  current,
  onSave,
  errorTable,
  cancelTo,
  cancelLabel,
}: Props) {
  // 입력값은 state로 관리한다. 실패해도 그대로 남는다
  const [visaType, setVisaType] = useState(initial?.visaType ?? '')
  const [applicationType, setApplicationType] = useState(initial?.applicationType ?? '')
  const [result, setResult] = useState(initial?.result ?? '')
  const [receivedDate, setReceivedDate] = useState(initial?.receivedDate ?? '')
  const [resultDate, setResultDate] = useState(initial?.resultDate ?? '')
  const [content, setContent] = useState(initial?.content ?? '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // 저장 중 401(재발급까지 실패): 로그인 안내를 따로 보여 준다
  const [needLogin, setNeedLogin] = useState(false)
  const today = seoulToday()

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setError(null)
    setNeedLogin(false)
    // select 셋·날짜 둘 중 빈 게 있으면 서버에 보내지 않고 화면에 머문다 (입력값은 그대로)
    if (!visaType || !applicationType || !result || !receivedDate || !resultDate) {
      // 선택지를 못 받아 select가 빈 경우는 새로고침을 안내한다
      const selectEmpty = !visaType || !applicationType || !result
      setError(
        optionsFailed && selectEmpty
          ? '선택지를 불러오지 못해 저장할 수 없습니다. 새로고침 후 다시 시도해 주세요'
          : '비자 종류·신청 종류·결과·날짜를 모두 입력해 주세요',
      )
      return
    }
    setSaving(true)
    try {
      // 미래 날짜는 input의 max가 막고(직접 타이핑하면 브라우저 검증이 막는다), 접수일≤결과일 순서만 서버의 400에 맡긴다. schoolId는 보내지 않는다
      await onSave({ visaType, applicationType, receivedDate, resultDate, result, content })
    } catch (err: unknown) {
      const info = describeError(err, errorTable, '저장하지 못했습니다')
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setError(info.message)
      setSaving(false) // 실패했을 때만 버튼을 되살린다 (성공하면 페이지가 바뀐다)
    }
  }

  return (
    <form className="panel" onSubmit={handleSubmit}>
      <div className="filters">
        {/* 학교는 고정: 고르는 칸이 아니라 글자로만 보여 준다 */}
        <span className="badge badge-school">
          <span className="sr-only">학교: </span>
          {schoolName}
        </span>
        <select aria-label="비자 종류" value={visaType} onChange={(e) => setVisaType(e.target.value)}>
          <option value="">비자 종류 선택</option>
          {withCurrent(options?.visaTypes, current?.visaType).map((o) => (
            <option key={o.code} value={o.code}>
              {o.name}
            </option>
          ))}
        </select>
        <select aria-label="신청 종류" value={applicationType} onChange={(e) => setApplicationType(e.target.value)}>
          <option value="">신청 종류 선택</option>
          {withCurrent(options?.applicationTypes, current?.applicationType).map((o) => (
            <option key={o.code} value={o.code}>
              {o.name}
            </option>
          ))}
        </select>
        <select aria-label="결과" value={result} onChange={(e) => setResult(e.target.value)}>
          <option value="">결과 선택</option>
          {withCurrent(options?.results, current?.result).map((o) => (
            <option key={o.code} value={o.code}>
              {o.name}
            </option>
          ))}
        </select>
      </div>
      {optionsFailed && (
        <Notice kind="error">
          {current ? '선택지 목록을 불러오지 못했습니다. 지금 값으로만 저장할 수 있습니다' : '선택지 목록을 불러오지 못했습니다'}
        </Notice>
      )}
      {/* 접수일·결과일: 나란히(좁으면 세로). 둘 다 오늘(서울)까지만. 빈 값 검사는 required 대신 위 제출 검사에서 한다 */}
      <div className="date-row">
        <label>
          접수일
          <input type="date" value={receivedDate} max={today} onChange={(e) => setReceivedDate(e.target.value)} />
        </label>
        <label>
          결과일
          <input type="date" value={resultDate} max={today} onChange={(e) => setResultDate(e.target.value)} />
        </label>
      </div>
      <div className="field">
        <label>
          본문 (선택)
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            maxLength={2000}
            rows={8}
            placeholder="준비한 서류, 팁 등을 남겨 주세요"
            className="full-width"
          />
        </label>
        <span className="hint meta">{content.length} / 2000</span>
      </div>
      {error && <Notice kind="error">{error}</Notice>}
      {needLogin && <ReloginNotice />}
      <div className="actions">
        <button type="submit" className="btn btn-primary" disabled={saving}>
          {saving ? '저장 중...' : '저장'}
        </button>
        <Link to={cancelTo} className="btn">
          {cancelLabel}
        </Link>
      </div>
    </form>
  )
}
