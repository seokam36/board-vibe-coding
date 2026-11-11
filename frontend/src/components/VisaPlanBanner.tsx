import { useState } from 'react'
import { Link, useLocation } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { useVisaPlan } from '../visa/visaPlanContext'
import {
  BANNER_CLOSE_LABEL,
  BANNER_LINK_TEXT,
  bannerDismissValue,
  bannerMessage,
  isBannerDismissed,
  saveBannerDismissed,
} from '../visa/visaBanner'

// 상단바 아래 비자 일정 띠. 단계(stage)가 있을 때만 뜨고, 불러오기 실패·미인증·비로그인이면 아무것도 안 그린다
// role="status": 에러 알림(alert)이 아니라 조용한 상태 안내
export default function VisaPlanBanner() {
  const { user } = useAuth()
  const { status, plan } = useVisaPlan()
  const { pathname } = useLocation()
  const [closedNow, setClosedNow] = useState<string[]>([]) // 이번 화면에서 닫은 값(저장소가 막혀도 닫히게)

  if (!user || status !== 'ready' || !plan?.stage || plan.expiryDate === null || plan.daysLeft === null) return null
  if (pathname === '/visa-plan') return null // 일정 화면 안에서는 숨김

  const stage = plan.stage
  const daysLeft = plan.daysLeft
  const userId = user.id
  const message = bannerMessage(stage, daysLeft)
  if (message === null) return null // 모르는 stage면 띠를 그리지 않는다 (예외로 전체 화면이 죽는 것 방지)
  const value = bannerDismissValue(plan.expiryDate, stage) // 단계 + 만료일 해시
  if (closedNow.includes(value) || isBannerDismissed(userId, value)) return null

  function close() {
    saveBannerDismissed(userId, value)
    setClosedNow((values) => [...values, value])
  }

  return (
    <div className="visa-banner" role="status">
      <span className="visa-banner-text">{message}</span>
      <Link to="/visa-plan">{BANNER_LINK_TEXT}</Link>
      <button type="button" className="visa-banner-close" aria-label={BANNER_CLOSE_LABEL} onClick={close}>
        ×
      </button>
    </div>
  )
}
