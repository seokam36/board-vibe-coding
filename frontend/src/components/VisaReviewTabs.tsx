import { Link, useLocation } from 'react-router'

// "후기 / 통계" 탭. 현재 주소의 쿼리(필터)를 그대로 들고 가서 탭을 오가도 조건이 유지된다
export default function VisaReviewTabs({ current }: { current: 'list' | 'stats' }) {
  const { search } = useLocation()
  return (
    <nav className="tabs" aria-label="비자 후기 보기 방식">
      <Link to={`/visa-reviews${search}`} aria-current={current === 'list' ? 'page' : undefined}>
        후기
      </Link>
      <Link to={`/visa-reviews/stats${search}`} aria-current={current === 'stats' ? 'page' : undefined}>
        통계
      </Link>
    </nav>
  )
}
