import { useEffect, useRef, useState } from 'react'
import { Link, useParams } from 'react-router'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import { describeError, ERROR_MESSAGES } from '../errorMessage'
import { formatDate } from '../formatDate'
import { MESSAGES_TRUNCATED, WITHDRAWN_PARTNER } from '../messageText'
import { useUnread } from '../messages/unreadContext'
import Loading from '../components/Loading'
import MessageComposer from '../components/MessageComposer'
import Notice from '../components/Notice'
import ReportForm from '../components/ReportForm'
import ReloginNotice from '../components/ReloginNotice'
import type { ConversationDetail, ConversationMessage } from '../types'

// 대화 화면 (/messages/:id): 위에 상대와 글, 가운데 쪽지(오래된 순), 아래 답장 칸. 로그인한 사람만 (App에서 RequireLogin으로 감쌈)
export default function ConversationPage() {
  const { id } = useParams()
  // key={id}: 주소의 대화 번호가 바뀌면 안쪽 화면을 통째로 새로 만든다.
  // (브라우저 기록으로 /messages/5 → /messages/3 으로 건너뛸 때 React가 화면을 재사용하면
  //  이전 대화 내용·쓰던 답장·상태가 새 대화에 남기 때문)
  return <ConversationView key={id ?? ''} id={id ?? ''} />
}

function ConversationView({ id }: { id: string }) {
  const safeId = encodeURIComponent(id) // '?' 같은 문자가 경로에 섞여 다른 API로 바뀌는 것을 막는다
  const { user } = useAuth()
  const { refresh: refreshUnread } = useUnread() // 읽음 처리 뒤 상단바 숫자 갱신 (함수가 안 바뀌어 아래 effect가 다시 돌지 않는다)
  const [conv, setConv] = useState<ConversationDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [needLogin, setNeedLogin] = useState(false) // 불러오다 401(재발급 실패)
  const endRef = useRef<HTMLDivElement>(null) // 쪽지 목록 끝: 열 때·답장했을 때 여기까지 내린다
  const count = conv?.messages.length ?? 0

  useEffect(() => {
    // 응답이 오기 전에 화면이 사라지거나 id가 바뀌면 늦게 온 응답을 버리기 위한 플래그
    let ignore = false
    api
      .get<ConversationDetail>(`/conversations/${safeId}`)
      .then((res) => {
        if (ignore) return
        setConv(res.data)
        setError(null)
        setNeedLogin(false)
        // 대화를 열었으니 "여기까지 봤다"고 서버에 알린다 (마지막 쪽지 번호). 쪽지가 없으면 보낼 게 없다
        const msgs = res.data.messages
        if (msgs.length > 0) {
          api
            .put(`/conversations/${safeId}/read`, { lastMessageId: msgs[msgs.length - 1].id })
            .then(() => refreshUnread()) // 읽음 처리가 끝나면 상단바 숫자를 다시 받는다
            .catch(() => {
              // 읽음 처리는 부가 동작이라 실패해도 대화 화면은 그대로 두고 에러를 무시한다
            })
        }
      })
      .catch((e: unknown) => {
        if (ignore) return
        setConv(null)
        const info = describeError(
          e,
          { 400: ERROR_MESSAGES.CONVERSATION_NOT_FOUND, 404: ERROR_MESSAGES.CONVERSATION_NOT_FOUND },
          ERROR_MESSAGES.CONVERSATION_LOAD_FAILED,
        )
        if (info.kind === 'needLogin') setNeedLogin(true)
        else setError(info.message)
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [safeId, refreshUnread])

  // 쪽지가 늘어나면(처음 불러왔을 때, 답장했을 때) 맨 아래가 보이게 내린다
  useEffect(() => {
    if (count > 0) endRef.current?.scrollIntoView({ block: 'end' })
  }, [count])

  // 답장 보내기: 성공하면 목록 끝에 붙인다. 실패하면 던져서 MessageComposer가 안내를 띄운다.
  // 보내는 곳은 주소가 아니라 "화면에 보이는 대화"(convId)다
  async function sendReply(convId: number, content: string) {
    const res = await api.post<ConversationMessage>(`/conversations/${convId}/messages`, { content })
    setConv((prev) => (prev && prev.id === convId ? { ...prev, messages: [...prev.messages, res.data] } : prev))
  }

  const partnerName = conv?.partner?.nickname ?? WITHDRAWN_PARTNER

  return (
    <div>
      {loading && <Loading />}
      {error && <Notice kind="error">{error}</Notice>}
      {needLogin && <ReloginNotice />}
      {conv && (
        <>
          <header className="panel conv-head">
            <h1 className="post-title">{partnerName}</h1>
            <p className="meta">
              {/* 글이 있으면 상세로 가는 링크, 지워졌으면 링크 없이 제목과 표시만 */}
              {conv.item.id !== null && !conv.item.deleted ? (
                <Link to={`/market/${conv.item.id}`}>{conv.item.title}</Link>
              ) : (
                <>
                  <span className="badge badge-DONE">삭제된 글</span>
                  <span>{conv.item.title}</span>
                </>
              )}
            </p>
          </header>
          {/* 신고: 학교 인증 회원만 (서버가 미인증은 403). 상대가 탈퇴했거나 글이 지워진 방도 신고할 수 있어 그런 조건은 안 본다 */}
          {user?.verifiedSchool && (
            <div className="actions">
              <ReportForm
                key={conv.id}
                url={`/conversations/${conv.id}/reports`}
                label="대화"
                onNeedLogin={() => {
                  setNeedLogin(true) // 안내는 대화 영역 밖에서 보여 준다 (로그인이 풀리면 이 버튼이 사라지므로)
                }}
              />
            </div>
          )}
          {conv.messages.length === 0 && <p className="panel-state">아직 쪽지가 없습니다</p>}
          {/* 서버가 최근 200통만 줬으면(hasMore) 목록 위에 안내 */}
          {conv.hasMore && <p className="hint msg-truncated">{MESSAGES_TRUNCATED}</p>}
          <ul className="msg-list" aria-label="쪽지 목록">
            {conv.messages.map((m) => (
              <li key={m.id} className={`msg ${m.mine ? 'msg-mine' : 'msg-theirs'}`}>
                {/* pre-wrap: 줄바꿈을 그대로 보여준다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
                <p className="msg-bubble pre-wrap">{m.content}</p>
                <span className="msg-time">
                  <span className="sr-only">{m.mine ? '내가 보냄 ' : '받은 쪽지 '}</span>
                  {formatDate(m.createdAt)}
                </span>
              </li>
            ))}
          </ul>
          <div ref={endRef}>
            {conv.partner === null ? (
              // 상대가 탈퇴했으면 서버가 새 쪽지를 409로 막는다. 답장 칸 대신 안내만
              <Notice kind="info">상대가 탈퇴해 더 이상 쪽지를 보낼 수 없어요</Notice>
            ) : user && !user.verifiedSchool ? (
              // 답장도 학교 인증 회원만 (서버 403). 로그인한 미인증 회원이면 칸 대신 안내.
              // 로그인이 풀려 user가 null이 된 경우는 안내로 바꾸지 않는다 (쓰던 답장을 지우지 않으려고)
              <p className="hint">
                학교 인증 후 답장할 수 있어요 <Link to="/me">학교 인증하기</Link>
              </p>
            ) : (
              <MessageComposer
                key={conv.id}
                label="답장 내용"
                placeholder="답장을 입력하세요"
                submitLabel="답장 보내기"
                errorTable={{
                  400: ERROR_MESSAGES.MESSAGE_CONTENT_INVALID,
                  403: '쪽지를 보내지 못했습니다. 새로고침 후 다시 시도해 주세요',
                  404: ERROR_MESSAGES.CONVERSATION_NOT_FOUND,
                  409: '상대가 탈퇴해 쪽지를 보낼 수 없습니다',
                }}
                fallbackError={ERROR_MESSAGES.MESSAGE_SEND_FAILED}
                send={(content) => sendReply(conv.id, content)}
              />
            )}
          </div>
        </>
      )}
      {/* 대화를 못 찾았을 때도 돌아갈 길이 있게 항상 보여 준다 */}
      <p>
        <Link to="/messages" className="btn">
          쪽지함으로
        </Link>
      </p>
    </div>
  )
}
