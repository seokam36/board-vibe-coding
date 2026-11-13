import { useCallback, useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import api from '../api'
import { describeError, ERROR_MESSAGES } from '../errorMessage'
import { useAuth } from '../auth/useAuth'
import { isAdmin } from '../auth/isAdmin'
import { formatDate } from '../formatDate'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import ReloginNotice from '../components/ReloginNotice'
import { useConfirm } from '../components/confirmContext'
import type { Report, ReportStatus } from '../types'

const ADMIN_ONLY = ERROR_MESSAGES.ADMIN_ONLY

// 신고 대상 종류별 문구·삭제 주소 (중고 글은 관리자도 DELETE /market-items/{id}로 지울 수 있다)
type TargetType = Report['target']['type']
// 대화는 삭제 API가 없다(기각만 가능). 삭제 주소 표에서 빼 두면 대화로 삭제를 부르는 코드는 타입 오류가 난다
type DeletableType = Exclude<TargetType, 'CONVERSATION'>
const TARGET_LABEL: Record<TargetType, string> = { POST: '글', COMMENT: '댓글', MARKET_ITEM: '중고 글', CONVERSATION: '대화' }
const BADGE_LABEL: Record<TargetType, string> = { POST: '글', COMMENT: '댓글', MARKET_ITEM: '중고', CONVERSATION: '쪽지' }
const DELETE_PATH: Record<DeletableType, (id: number) => string> = {
  POST: (id) => `/posts/${id}`,
  COMMENT: (id) => `/comments/${id}`,
  MARKET_ITEM: (id) => `/market-items/${id}`,
}

// 관리자 전용 신고 관리 화면: 미처리 / 기각 탭, 미처리 신고에는 "삭제"·"문제 없음" 버튼
export default function AdminReportsPage() {
  const { user } = useAuth()
  const confirm = useConfirm() // 삭제 전 확인 창
  // 탭은 주소의 ?status= 로 유지한다 (새로고침·뒤로가기에도 같은 탭). 모르는 값이면 미처리
  const [params, setParams] = useSearchParams()
  const status: ReportStatus = params.get('status') === 'DISMISSED' ? 'DISMISSED' : 'PENDING'
  const admin = isAdmin(user)

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
      const info = describeError(e, { 403: ADMIN_ONLY }, '신고 목록을 불러오지 못했습니다')
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setLoadError(info.message)
    }
  }, [status])

  useEffect(() => {
    if (!admin) return // 관리자가 아니면 요청 자체를 보내지 않는다
    // 탭·로그인 상태가 바뀔 때 목록을 읽는다 (렌더 직후 비동기로 실행)
    Promise.resolve().then(fetchList)
  }, [admin, fetchList])

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
    const info = describeError(err, { 403: ADMIN_ONLY, 404: gone, 409: gone }, fallback)
    if (info.kind === 'needLogin') setNeedLogin(true)
    else {
      setActionError(info.message)
      if (info.status === 404 || info.status === 409) await fetchList()
    }
  }

  function startAction() {
    setBusy(true)
    setActionError(null)
    setNotice(null)
    setNeedLogin(false)
  }

  async function handleDelete(r: Report) {
    const type = r.target.type
    if (type === 'CONVERSATION') return // 대화는 삭제할 수 없다 (버튼도 없지만 타입으로도 막는다)
    const what = TARGET_LABEL[type]
    // 되돌릴 수 없는 동작이라 먼저 확인한다
    if (!(await confirm({ message: `신고된 ${what}을(를) 삭제할까요?`, confirmLabel: '삭제', danger: true }))) return
    startAction()
    try {
      await api.delete(DELETE_PATH[type](r.target.id))
      setNotice('처리했습니다')
      await fetchList() // 목록을 다시 읽을 때까지 버튼을 계속 막는다
    } catch (err: unknown) {
      await handleFail(err, ERROR_MESSAGES.DELETE_FAILED, ERROR_MESSAGES.alreadyDeleted(what))
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
      await handleFail(err, ERROR_MESSAGES.ACTION_FAILED, '이미 처리됐거나 사라진 신고입니다')
    } finally {
      setBusy(false)
    }
  }

  // 관리자가 아니면 안내만 (비로그인·일반 회원 모두). 진짜 보호는 서버의 403이다.
  // 단, 처리 중 로그인이 풀려(401) 관리자가 아니게 된 경우엔 로그인 안내를 보여 준다
  if (!admin) return needLogin ? <ReloginNotice /> : <Notice kind="error">{ADMIN_ONLY}</Notice>

  return (
    <div>
      <h1>신고 관리</h1>
      {/* tabs: 선택된 탭(aria-pressed)을 굵게+밑줄로 보여 주는 CSS (index.css). disabled로 표현하지 않는다 */}
      <div className="tabs">
        <button
          type="button"
          onClick={() => changeTab('PENDING')}
          aria-pressed={status === 'PENDING'}
          disabled={busy}
        >
          미처리
        </button>
        <button
          type="button"
          onClick={() => changeTab('DISMISSED')}
          aria-pressed={status === 'DISMISSED'}
          disabled={busy}
        >
          기각
        </button>
      </div>
      {loadError && (
        <Notice kind="error">
          {loadError}{' '}
          {loadError !== ADMIN_ONLY && (
            <button type="button" className="btn btn-sm" onClick={retry}>
              다시 시도
            </button>
          )}
        </Notice>
      )}
      {reports === null && !loadError && !needLogin && <Loading />}
      {/* 신고 한 건 = 판 안의 행 */}
      {reports && (
        <div className="panel panel-flush">
          {reports.length === 0 && <p className="panel-state">신고가 없습니다</p>}
          <ul className="rows">
            {reports.map((r) => (
              <li key={r.id} className="row">
                <div className="row-title">
                  <span className={`badge badge-${r.target.type}`}>{BADGE_LABEL[r.target.type]}</span>
                  {r.target.type === 'CONVERSATION' ? (
                    // 대화 보기 주소에는 방 id(target.id)가 아니라 "신고 id"(r.id)를 쓴다
                    <Link to={`/admin/reports/${r.id}/conversation`}>대화 보기</Link>
                  ) : (
                    // 중고 글은 postId가 없고 /market/{id}로 간다
                    <Link to={r.target.type === 'MARKET_ITEM' ? `/market/${r.target.id}` : `/posts/${r.target.postId}`}>글로 가기</Link>
                  )}
                </div>
                {/* pre-wrap: 줄바꿈을 그대로. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
                <p className="pre-wrap-break">{r.target.preview}</p>
                <dl className="report-meta">
                  <div>
                    {/* 대화 신고의 author는 "신고자가 아닌 쪽 참가자"라서 라벨을 바꾼다 */}
                    <dt>{r.target.type === 'CONVERSATION' ? '대화 상대' : '대상 작성자'}</dt>
                    <dd>{r.target.author?.nickname ?? (r.target.type === 'CONVERSATION' ? '탈퇴한 회원 또는 상대 없음' : '탈퇴한 회원 또는 작성자 없음')}</dd>
                  </div>
                  <div>
                    <dt>사유</dt>
                    <dd>{r.reason.name}</dd>
                  </div>
                  <div>
                    <dt>상세</dt>
                    <dd className="pre-wrap-break">{r.detail || '-'}</dd>
                  </div>
                  <div>
                    <dt>신고자</dt>
                    <dd>{r.reporter?.nickname ?? '탈퇴한 회원'}</dd>
                  </div>
                  <div>
                    <dt>날짜</dt>
                    <dd>{formatDate(r.createdAt)}</dd>
                  </div>
                </dl>
                {status === 'PENDING' && (
                  <div className="actions">
                    {/* 대화 신고는 삭제 없이 [문제 없음](기각)만 */}
                    {r.target.type !== 'CONVERSATION' && (
                      <button type="button" className="btn btn-sm btn-danger" onClick={() => handleDelete(r)} disabled={busy}>
                        삭제
                      </button>
                    )}
                    <button type="button" className="btn btn-sm" onClick={() => handleDismiss(r)} disabled={busy}>
                      문제 없음
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {notice && <Notice kind="success">{notice}</Notice>}
      {actionError && <Notice kind="error">{actionError}</Notice>}
      {needLogin && <ReloginNotice />}
    </div>
  )
}
