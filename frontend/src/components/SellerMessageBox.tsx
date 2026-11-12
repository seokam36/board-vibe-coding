import { useState } from 'react'
import type { ReactNode } from 'react'
import { Link, useNavigate } from 'react-router'
import api from '../api'
import { canActInSchool } from '../auth/canActInSchool'
import { useAuth } from '../auth/useAuth'
import { ERROR_MESSAGES } from '../errorMessage'
import type { MarketItem, StartConversationResponse } from '../types'
import MessageComposer from './MessageComposer'

// 거래완료 글 안내 (화면 안내와 서버 409 실패 문구가 같은 뜻이 되게 한 곳에 둔다)
const DONE_NOTICE = '거래가 완료된 글이라 새 대화를 시작할 수 없어요. 이미 나눈 대화는 쪽지함에서 이어 갈 수 있어요'

// 중고 글 상세 아래의 "판매자에게 쪽지" 칸.
// 내 글이면 칸 자체를 그리지 않고, 보낼 수 없는 사람(비로그인·미인증·다른 학교·거래완료 글)에게는 입력칸 대신 이유 한 줄만 보여 준다.
// 화면에서 막는 건 편의일 뿐, 진짜 보호는 서버의 403·409이다
export default function SellerMessageBox({ item }: { item: MarketItem }) {
  const { user } = useAuth()
  const navigate = useNavigate()
  // 한 번이라도 보내기를 눌렀으면 true. 그 뒤 로그인이 풀려(user 비워짐) 입력칸이 사라지면 재로그인 안내와 쓴 글이 같이 날아가므로 칸을 유지한다
  const [submitted, setSubmitted] = useState(false)

  if (user && item.seller.id === user.id) return null // 내 글에는 칸이 없다

  let hint: ReactNode = null
  if (!user) {
    hint = (
      <>
        로그인하면 판매자에게 쪽지를 보낼 수 있습니다 <Link to="/login">로그인</Link>
      </>
    )
  } else if (!user.verifiedSchool) {
    hint = (
      <>
        학교 인증 후 같은 학교 글의 판매자에게 쪽지를 보낼 수 있어요 <Link to="/me">학교 인증하기</Link>
      </>
    )
  } else if (!canActInSchool(user, item.school.id)) {
    hint = `인증한 학교(${user.verifiedSchool.name}) 글에만 쪽지를 보낼 수 있어요`
  } else if (item.status.code === 'DONE') {
    // 서버는 이미 대화방이 있으면 거래완료 글에도 쪽지를 받는다. 새 대화만 막히므로 쪽지함으로 가는 길을 같이 안내한다
    hint = (
      <>
        {DONE_NOTICE} <Link to="/messages">쪽지함 가기</Link>
      </>
    )
  }

  if (hint && !submitted) {
    return <p className="hint seller-message-hint">{hint}</p>
  }

  // 첫 쪽지를 보내고, 성공하면 만들어진(또는 이미 있던) 대화 화면으로 간다
  async function send(content: string) {
    const res = await api.post<StartConversationResponse>(`/market-items/${encodeURIComponent(String(item.id))}/conversations`, {
      content,
    })
    navigate(`/messages/${res.data.conversationId}`)
  }

  return (
    <section className="seller-message" aria-labelledby="seller-message-title">
      <h2 id="seller-message-title">판매자에게 쪽지</h2>
      <MessageComposer
        label="쪽지 내용"
        placeholder={`${item.seller.nickname} 님에게 보낼 쪽지를 입력하세요`}
        submitLabel="쪽지 보내기"
        errorTable={{
          400: ERROR_MESSAGES.MESSAGE_CONTENT_INVALID,
          403: ERROR_MESSAGES.SCHOOL_GATE_POST_ACTION,
          404: ERROR_MESSAGES.alreadyDeleted('글'),
          409: DONE_NOTICE,
        }}
        fallbackError={ERROR_MESSAGES.MESSAGE_SEND_FAILED}
        send={send}
        onSubmitStart={() => setSubmitted(true)}
      />
    </section>
  )
}
