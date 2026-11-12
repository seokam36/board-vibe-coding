import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import api from '../api'
import { describeError } from '../errorMessage'
import { formatDate } from '../formatDate'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import ReloginNotice from '../components/ReloginNotice'
import { WITHDRAWN_PARTNER } from '../messageText'
import type { ConversationSummary } from '../types'

// 쪽지함 (/messages): 내가 참여한 대화를 마지막 쪽지가 최근인 순으로 보여 준다. 로그인한 사람만 (App에서 RequireLogin으로 감쌈)
export default function MessageListPage() {
  // null이면 아직 못 불러온 상태
  const [conversations, setConversations] = useState<ConversationSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [needLogin, setNeedLogin] = useState(false) // 불러오다 401(재발급 실패): 로그인 안내

  useEffect(() => {
    // 응답이 오기 전에 화면이 사라지면 늦게 온 응답을 버리기 위한 플래그
    let ignore = false
    api
      .get<ConversationSummary[]>('/conversations')
      .then((res) => {
        if (ignore) return
        setConversations(res.data)
        setError(null)
        setNeedLogin(false)
      })
      .catch((e: unknown) => {
        if (ignore) return
        const info = describeError(e, {}, '쪽지함을 불러오지 못했습니다')
        if (info.kind === 'needLogin') setNeedLogin(true)
        else setError(info.message)
      })
    return () => {
      ignore = true
    }
  }, [])

  return (
    <div>
      <h1 className="post-title">쪽지함</h1>
      {error && <Notice kind="error">{error}</Notice>}
      {needLogin && <ReloginNotice />}
      {conversations === null && !error && !needLogin && <Loading prefix="쪽지함" />}
      {conversations && (
        <div className="panel panel-flush">
          {conversations.length === 0 && <p className="panel-state">아직 쪽지가 없습니다</p>}
          <ul className="rows">
            {conversations.map((c) => (
              <li key={c.id} className="row conv-row">
                {/* 줄 전체가 링크: 상대 닉네임 · 글 제목 · 마지막 쪽지 · 시각 */}
                <Link to={`/messages/${c.id}`} className="conv-link">
                  <span className="conv-top">
                    <span className="conv-partner">{c.partner?.nickname ?? WITHDRAWN_PARTNER}</span>
                    <span className="meta">{formatDate(c.lastMessage.createdAt)}</span>
                  </span>
                  <span className="conv-item meta">
                    {c.item.deleted && <span className="badge badge-DONE">삭제된 글</span>}
                    <span>{c.item.title}</span>
                  </span>
                  {/* 길면 한 줄에서 줄임표(…)로 자른다. 내가 보낸 쪽지에는 "나: "를 붙인다 */}
                  <span className="conv-last">
                    {c.lastMessage.mine ? '나: ' : ''}
                    {c.lastMessage.content}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
