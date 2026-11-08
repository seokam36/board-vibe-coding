import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import api from '../api'
import { NETWORK_ERROR_MESSAGE, getStatus, isNetworkError } from '../errorMessage'
import { useAuth } from '../auth/useAuth'
import { formatDate } from '../formatDate'
import ReloginNotice from '../components/ReloginNotice'
import type { Report, ReportStatus } from '../types'

const ADMIN_ONLY = '관리자만 볼 수 있습니다'

// 선택된 탭을 눈으로도 알아볼 수 있게 하는 모양 (disabled로 표현하지 않는다)
function tabStyle(selected: boolean) {
  return { fontWeight: selected ? 'bold' : 'normal', textDecoration: selected ? 'underline' : 'none' } as const
}

// 관리자 전용 신고 관리 화면: 미처리 / 기각 탭, 미처리 신고에는 "삭제"·"문제 없음" 버튼
export default function AdminReportsPage() {
  const { user } = useAuth()
  // 탭은 주소의 ?status= 로 유지한다 (새로고침·뒤로가기에도 같은 탭). 모르는 값이면 미처리
  const [params, setParams] = useSearchParams()
  const status: ReportStatus = params.get('status') === 'DISMISSED' ? 'DISMISSED' : 'PENDING'
  const isAdmin = user?.role === 'ADMIN'

  const [reports, setReports] = useState<Report[] | null>(null) // null이면 아직 못 불러온 상태
  const [loadError, setLoadError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false) // 처리 요청·목록 재조회 중 연타 방지
  const [actionError, setActionError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null) // 처리 성공 안내
  const [needLogin, setNeedLogin] = useState(false)
  // 요청 번호: 가장 마지막에 보낸 목록 요청의 응답만 화면에 반영한다 (늦게 온 옛 응답 무시)
  const requestRef = useRef(0)

  // 현재 탭의 목록을 읽는다. 끝날 때까지 기다릴 수 있게 Promise를 돌려준다
  const fetchList = useCallback(async () => {
    const myId = ++requestRef.current
    try {
      const res = await api.get<Report[]>('/admin/reports', { params: { status } })
      if (myId !== requestRef.current) return
      setReports(res.data)
      setLoadError(null)
      setNeedLogin(false)
    } catch (e: unknown) {
      if (myId !== requestRef.current) return
      setReports(null) // 실패했는데 옛 목록과 버튼이 남지 않게 비운다
      const code = getStatus(e)
      if (isNetworkError(e)) setLoadError(NETWORK_ERROR_MESSAGE)
      else if (code === 401) setNeedLogin(true)
      else if (code === 403) setLoadError(ADMIN_ONLY)
      else setLoadError('신고 목록을 불러오지 못했습니다')
    }
  }, [status])

  useEffect(() => {
    if (!isAdmin) return // 관리자가 아니면 요청 자체를 보내지 않는다
    // 탭·로그인 상태가 바뀔 때 목록을 읽는다 (렌더 직후 비동기로 실행)
    Promise.resolve().then(fetchList)
  }, [isAdmin, fetchList])

  function changeTab(next: ReportStatus) {
    setReports(null) // 이전 탭 목록이 잠깐 보이지 않게 비운다
    setLoadError(null)
    setActionError(null)
    setNotice(null)
    // replace: 탭을 누를 때마다 뒤로가기 기록이 쌓이지 않게 한다
    setParams({ status: next }, { replace: true })
  }

  function retry() {
    setLoadError(null)
    // 탭·로그인 상태가 바뀔 때 목록을 읽는다 (렌더 직후 비동기로 실행)
    Promise.resolve().then(fetchList)
  }

  // 처리(삭제·문제 없음) 실패 공통 안내. 404/409는 이미 처리된 것이라 목록을 다시 읽어 맞춘다
  async function handleFail(err: unknown, fallback: string, gone: string) {
    const code = getStatus(err)
    if (isNetworkError(err)) setActionError(NETWORK_ERROR_MESSAGE)
    else if (code === 401) setNeedLogin(true)
    else if (code === 403) setActionError(ADMIN_ONLY)
    else if (code === 404 || code === 409) {
      setActionError(gone)
      await fetchList()
    } else setActionError(fallback)
  }

  function startAction() {
    setBusy(true)
    setActionError(null)
    setNotice(null)
    setNeedLogin(false)
  }

  async function handleDelete(r: Report) {
    const what = r.target.type === 'POST' ? '글' : '댓글'
    // 되돌릴 수 없는 동작이라 먼저 확인한다
    if (!window.confirm(`신고된 ${what}을(를) 삭제할까요?`)) return
    startAction()
    try {
      await api.delete(r.target.type === 'POST' ? `/posts/${r.target.id}` : `/comments/${r.target.id}`)
      setNotice('처리했습니다')
      await fetchList() // 목록을 다시 읽을 때까지 버튼을 계속 막는다
    } catch (err: unknown) {
      await handleFail(err, '삭제하지 못했습니다', `이미 삭제된 ${what}입니다`)
    } finally {
      setBusy(false)
    }
  }

  async function handleDismiss(r: Report) {
    startAction()
    try {
      await api.put(`/admin/reports/${r.id}/dismiss`)
      setNotice('처리했습니다')
      await fetchList()
    } catch (err: unknown) {
      await handleFail(err, '처리하지 못했습니다', '이미 처리됐거나 사라진 신고입니다')
    } finally {
      setBusy(false)
    }
  }

  // 관리자가 아니면 안내만 (비로그인·일반 회원 모두). 진짜 보호는 서버의 403이다.
  // 단, 처리 중 로그인이 풀려(401) 관리자가 아니게 된 경우엔 로그인 안내를 보여 준다
  if (!isAdmin) return needLogin ? <ReloginNotice /> : <p role="alert">{ADMIN_ONLY}</p>

  return (
    <div>
      <h1>신고 관리</h1>
      <div>
        <button
          type="button"
          onClick={() => changeTab('PENDING')}
          aria-pressed={status === 'PENDING'}
          disabled={busy}
          style={tabStyle(status === 'PENDING')}
        >
          미처리
        </button>{' '}
        <button
          type="button"
          onClick={() => changeTab('DISMISSED')}
          aria-pressed={status === 'DISMISSED'}
          disabled={busy}
          style={tabStyle(status === 'DISMISSED')}
        >
          기각
        </button>
      </div>
      {loadError && (
        <p role="alert">
          {loadError} {loadError !== ADMIN_ONLY && <button type="button" onClick={retry}>다시 시도</button>}
        </p>
      )}
      {reports === null && !loadError && !needLogin && <p>불러오는 중...</p>}
      {reports && reports.length === 0 && <p>신고가 없습니다</p>}
      <ul>
        {(reports ?? []).map((r) => (
          <li key={r.id}>
            <strong>[{r.target.type === 'POST' ? '글' : '댓글'}]</strong>{' '}
            <Link to={`/posts/${r.target.postId}`}>글로 가기</Link>
            {/* pre-wrap: 줄바꿈을 그대로. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
            <p style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{r.target.preview}</p>
            <small>대상 작성자: {r.target.author?.nickname ?? '탈퇴한 회원 또는 작성자 없음'}</small>
            <br />
            <small>사유: {r.reason.name}</small>
            <br />
            <small style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>상세: {r.detail || '-'}</small>
            <br />
            <small>
              신고자: {r.reporter?.nickname ?? '탈퇴한 회원'} · {formatDate(r.createdAt)}
            </small>
            {status === 'PENDING' && (
              <div>
                <button type="button" onClick={() => handleDelete(r)} disabled={busy}>
                  삭제
                </button>{' '}
                <button type="button" onClick={() => handleDismiss(r)} disabled={busy}>
                  문제 없음
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
      {notice && <p role="status">{notice}</p>}
      {actionError && <p role="alert">{actionError}</p>}
      {needLogin && <ReloginNotice />}
    </div>
  )
}
