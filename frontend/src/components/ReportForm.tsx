import { useState } from 'react'
import type { FormEvent } from 'react'
import api from '../api'
import { describeError, errorText } from '../errorMessage'
import type { ReportReason } from '../types'
import Loading from './Loading'
import Notice from './Notice'

const DETAIL_MAX = 300

// 글·댓글 아래에 붙는 "신고" 버튼 + 펼쳐지는 신고 양식.
// url: 신고를 보낼 주소(/posts/{id}/reports 또는 /comments/{id}/reports), label: '글' 또는 '댓글'(안내 문구용)
// onNeedLogin: 401(재발급까지 실패)이면 부모가 로그인 안내를 띄우게 알린다
// (로그인이 풀리면 이 컴포넌트가 사라지므로 안내를 여기서 띄우면 안 보인다)
export default function ReportForm({
  url,
  label,
  onNeedLogin,
}: {
  url: string
  label: '글' | '댓글'
  onNeedLogin: () => void
}) {
  const [open, setOpen] = useState(false)
  // 사유 목록. null이면 아직 못 받은 상태
  const [reasons, setReasons] = useState<ReportReason[] | null>(null)
  const [reasonsError, setReasonsError] = useState<string | null>(null)
  const [reason, setReason] = useState('')
  const [detail, setDetail] = useState('')
  const [sending, setSending] = useState(false)
  const [message, setMessage] = useState<string | null>(null) // 성공·실패 안내
  const [done, setDone] = useState(false) // 접수 완료 또는 이미 신고함 → 양식을 닫고 다시 못 열게 한다

  // 사유 목록은 양식을 처음 열 때만 받는다 (안 누르는 사람은 요청 낭비가 없다)
  function loadReasons() {
    setReasonsError(null)
    api
      .get<ReportReason[]>('/report-reasons')
      .then((res) => setReasons(res.data))
      .catch((e: unknown) => setReasonsError(errorText(e, {}, '신고 사유를 불러오지 못했습니다')))
  }

  function handleOpen() {
    setOpen(true)
    setMessage(null)
    if (!reasons) loadReasons()
  }

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    if (!reason) {
      setMessage('신고 사유를 골라 주세요')
      return
    }
    setSending(true)
    setMessage(null)
    try {
      // 상세 내용은 비워 두면 보내지 않는다
      await api.post(url, { reason, ...(detail.trim() ? { detail: detail.trim() } : {}) })
      setMessage('신고가 접수됐습니다')
      setDone(true)
      setOpen(false)
    } catch (err: unknown) {
      const info = describeError(
        err,
        {
          409: `이미 신고한 ${label}입니다`,
          404: `이미 삭제된 ${label}입니다`,
          403: '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
          400: '내용을 확인해 주세요 (자기 것은 신고할 수 없고, 사유는 필수입니다)',
        },
        '신고하지 못했습니다',
      )
      if (info.kind === 'needLogin') onNeedLogin()
      else {
        setMessage(info.message)
        // 409(이미 신고함)·404(이미 지워진 대상)이면 더 보낼 수 없으니 양식을 닫는다
        if (info.status === 409 || info.status === 404) {
          setDone(true)
          setOpen(false)
        }
      }
    } finally {
      setSending(false)
    }
  }

  return (
    <div className={open ? 'report-open' : undefined}>
      {!open && !done && (
        <button type="button" className="btn btn-sm" onClick={handleOpen} aria-label={`${label} 신고`}>
          신고
        </button>
      )}
      {open && (
        <form className="report-box" onSubmit={handleSubmit}>
          {/* 사유 목록: 로딩 중 / 실패(다시 시도만) / 성공(셀렉트) */}
          {reasonsError ? (
            <Notice kind="error">
              {reasonsError}{' '}
              <button type="button" className="btn btn-sm" onClick={loadReasons}>다시 시도</button>
            </Notice>
          ) : !reasons ? (
            <Loading prefix="사유" />
          ) : (
            <select aria-label="신고 사유" value={reason} onChange={(e) => setReason(e.target.value)}>
              <option value="">사유를 고르세요</option>
              {reasons.map((r) => (
                <option key={r.code} value={r.code}>
                  {r.name}
                </option>
              ))}
            </select>
          )}
          <textarea
            aria-label="신고 상세 내용 (선택)"
            value={detail}
            onChange={(e) => setDetail(e.target.value)}
            maxLength={DETAIL_MAX}
            rows={2}
            placeholder="자세한 내용 (선택)"
            className="full-width"
          />
          <small>
            {detail.length}/{DETAIL_MAX}
          </small>{' '}
          <button type="submit" className="btn btn-sm" disabled={sending || !reasons}>
            {sending ? '보내는 중...' : '신고 보내기'}
          </button>{' '}
          <button type="button" className="btn btn-sm" onClick={() => setOpen(false)} disabled={sending}>
            취소
          </button>
        </form>
      )}
      {message && <Notice kind="error">{message}</Notice>}
    </div>
  )
}
