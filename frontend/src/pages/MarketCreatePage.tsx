import { useState } from 'react'
import { useNavigate } from 'react-router'
import api from '../api'
import { ERROR_MESSAGES } from '../errorMessage'
import MarketItemForm from '../components/MarketItemForm'
import MarketWriteGate from '../components/MarketWriteGate'
import { useAuth } from '../auth/useAuth'
import type { MarketItem, MarketItemRequest } from '../types'

// 중고·나눔 글쓰기. 학교 인증을 마친 회원만 쓸 수 있다 (학교는 서버가 인증 학교로 정한다)
export default function MarketCreatePage() {
  const { user } = useAuth()
  // 저장 중 로그인이 풀려 user가 null이 돼도 폼(입력값)을 지키려고, 인증 회원이었음을 기억해 둔다
  const [wasVerified, setWasVerified] = useState(false)
  const verified = !!user?.verifiedSchool
  if (verified && !wasVerified) setWasVerified(true) // 렌더 중 갱신(허용되는 패턴)
  const canWrite = user ? verified : wasVerified
  const navigate = useNavigate()

  async function save(body: MarketItemRequest) {
    const res = await api.post<MarketItem>('/market-items', body)
    navigate(`/market/${res.data.id}`) // 새 글 상세로 이동
  }

  if (!canWrite) {
    return (
      <div>
        <h1>중고·나눔 글쓰기</h1>
        <MarketWriteGate user={user} />
      </div>
    )
  }

  return (
    <div>
      <h1>중고·나눔 글쓰기</h1>
      <MarketItemForm
        onSave={save}
        errorTable={{
          400: ERROR_MESSAGES.MARKET_FIELDS_INVALID,
          403: ERROR_MESSAGES.VISA_SCHOOL_REQUIRED_RETRY,
        }}
        cancelTo="/market"
      />
    </div>
  )
}
