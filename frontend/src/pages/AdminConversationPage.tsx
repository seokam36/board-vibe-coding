import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import { isAdmin } from '../auth/isAdmin'
import { describeError, ERROR_MESSAGES } from '../errorMessage'
import { formatDate } from '../formatDate'
import { WITHDRAWN_PARTNER } from '../messageText'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import ReloginNotice from '../components/ReloginNotice'
import type { AdminConversationView } from '../types'

// 없는 신고(404)와 대화 신고가 아닌 신고(404), 숫자가 아닌 주소(400)가 모두 이 문구다
const REPORT_NOT_CONVERSATION = '없는 신고이거나 대화 신고가 아닙니다'

// 관리자가 신고된 대화를 읽는 화면 (/admin/reports/:id/conversation, :id = 신고 id). 읽기 전용
export default function AdminConversationPage() {
  const { id } = useParams()
  // key={id}: 주소의 신고 번호가 바뀌면 안쪽 화면을 새로 만든다 (이전 대화 내용이 남지 않게)
  return <ConversationReader key={id ?? ''} id={id ?? ''} />
}

function ConversationReader({ id }: { id: string }) {
  const safeId = encodeURIComponent(id) // '?' 같은 문자가 경로에 섞여 다른 API로 바뀌는 것을 막는다
  const { user } = useAuth()
  const admin = isAdmin(user)
  const [view, setView] = useState<AdminConversationView | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retryable, setRetryable] = useState(false) // 다시 시도해 볼 만한 실패(네트워크·서버 오류)인가
  const [needLogin, setNeedLogin] = useState(false) // 불러오다 401(재발급 실패)
  const [attempt, setAttempt] = useState(0) // [다시 시도]를 누를 때마다 늘려서 불러오기를 다시 돌린다

  useEffect(() => {
    if (!admin) return // 관리자가 아니면 요청 자체를 보내지 않는다 (진짜 보호는 서버의 403)
    // 응답이 오기 전에 화면이 사라지거나 번호가 바뀌면 늦게 온 응답을 버리기 위한 플래그
    let ignore = false
    api
      .get<AdminConversationView>(`/admin/reports/${safeId}/conversation`)
      .then((res) => {
        if (ignore) return
        setView(res.data)
        setError(null)
        setNeedLogin(false)
      })
      .catch((e: unknown) => {
        if (ignore) return
        setView(null)
        const info = describeError(
          e,
          { 400: REPORT_NOT_CONVERSATION, 403: ERROR_MESSAGES.ADMIN_ONLY, 404: REPORT_NOT_CONVERSATION },
          ERROR_MESSAGES.CONVERSATION_LOAD_FAILED,
        )
        if (info.kind === 'needLogin') setNeedLogin(true)
        else {
          setError(info.message)
          // 주소·권한 문제(400·403·404)는 다시 해도 같으니 버튼을 숨긴다
          setRetryable(info.status !== 400 && info.status !== 403 && info.status !== 404)
        }
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [safeId, admin, attempt])

  function retry() {
    setError(null)
    setLoading(true)
    setAttempt((n) => n + 1)
  }

  // 관리자가 아니면 안내만 (비로그인·일반 회원 모두. 신고 목록 화면과 같은 방식).
  // 보는 중에 로그인이 풀려(401) 관리자가 아니게 된 경우엔 로그인 안내를 보여 준다
  if (!admin) {
    return needLogin ? <ReloginNotice /> : <Notice kind="error">{ERROR_MESSAGES.ADMIN_ONLY}</Notice>
  }

  // 보낸 사람이 구매자인지 판매자인지 (탈퇴해서 sender가 null이면 알 수 없다)
  function roleOf(sender: { id: number } | null): '구매자' | '판매자' | null {
    if (!view || sender === null) return null
    if (view.buyer !== null && sender.id === view.buyer.id) return '구매자'
    if (view.seller !== null && sender.id === view.seller.id) return '판매자'
    return null
  }

  return (
    <div>
      <h1>신고된 대화</h1>
      {/* 대화가 실제로 보일 때만 안내한다 (로딩·오류 중에는 열람 중이 아니다) */}
      {view && <Notice kind="info">관리자가 신고된 대화를 열람 중입니다. 읽기만 할 수 있어요</Notice>}
      {loading && <Loading />}
      {error && (
        <Notice kind="error">
          {error}{' '}
          {retryable && (
            <button type="button" className="btn btn-sm" onClick={retry}>
              다시 시도
            </button>
          )}
        </Notice>
      )}
      {needLogin && <ReloginNotice />}
      {view && (
        <>
          <header className="panel conv-head">
            <h2 className="post-title">{view.itemTitle}</h2>
            <dl className="report-meta">
              <div>
                <dt>구매자</dt>
                <dd>{view.buyer?.nickname ?? WITHDRAWN_PARTNER}</dd>
              </div>
              <div>
                <dt>판매자</dt>
                <dd>{view.seller?.nickname ?? WITHDRAWN_PARTNER}</dd>
              </div>
            </dl>
          </header>
          {view.messages.length === 0 && <p className="panel-state">아직 쪽지가 없습니다</p>}
          {/* 구매자는 왼쪽, 판매자는 오른쪽 말풍선. 누구인지 모르면(탈퇴) 왼쪽 */}
          <ul className="msg-list" aria-label="쪽지 목록">
            {view.messages.map((m) => {
              const role = roleOf(m.sender)
              return (
                <li key={m.id} className={`msg ${role === '판매자' ? 'msg-mine' : 'msg-theirs'}`}>
                  <span className="msg-sender">
                    {m.sender?.nickname ?? WITHDRAWN_PARTNER}
                    {role && ` · ${role}`}
                  </span>
                  {/* pre-wrap-break: 줄바꿈을 그대로 보여 주고 긴 주소도 줄을 바꾼다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
                  <p className="msg-bubble pre-wrap-break">{m.content}</p>
                  <span className="msg-time">{formatDate(m.createdAt)}</span>
                </li>
              )
            })}
          </ul>
        </>
      )}
      {/* 어떤 상태에서도 돌아갈 길이 있게 항상 보여 준다 */}
      <p>
        <Link to="/admin/reports" className="btn">
          신고 목록으로
        </Link>
      </p>
    </div>
  )
}
