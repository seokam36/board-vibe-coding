import { useState } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { isAdmin } from '../auth/isAdmin'
import { SITE_NAME } from '../siteName'
import Notice from './Notice'

// 모든 화면 맨 위 한 줄: 왼쪽 홈 링크, 오른쪽 로그인 상태에 따른 메뉴
export default function NavBar() {
  const { user, logout } = useAuth()
  const [error, setError] = useState<string | null>(null)

  async function handleLogout() {
    setError(null)
    try {
      await logout()
    } catch {
      // 서버 쿠키가 아직 살아 있으니 로그인 상태는 그대로 두고, 눌렀는데 반응이 없어 보이지 않게 문구만 띄운다
      setError('로그아웃하지 못했습니다. 다시 시도해 주세요')
    }
  }

  return (
    <header className="nav-wrap">
      {/* nav-bar: 좁은 화면(모바일)에서 메뉴가 넘치지 않고 줄바꿈되게 하는 레이아웃 클래스 (index.css) */}
      <nav className="nav-bar">
        <Link to="/" className="nav-brand">
          {SITE_NAME}
        </Link>
        <span className="nav-menu">
          {user ? (
            <>
              <span>{user.nickname} 님</span>
              {/* 학교 인증을 마친 회원 표시. 학교 이름은 마우스를 올리거나 스크린리더로 알 수 있다 */}
              {user.verifiedSchool && (
                <span className="badge badge-verified" title={user.verifiedSchool.name}>
                  인증
                  <span className="sr-only"> ({user.verifiedSchool.name} 인증 회원)</span>
                </span>
              )}
              <Link to="/me">내 정보</Link>
              {/* 관리자에게만 보이는 메뉴 */}
              {isAdmin(user) && <Link to="/admin/reports">신고 관리</Link>}
              <button type="button" className="btn btn-sm" onClick={handleLogout}>
                로그아웃
              </button>
            </>
          ) : (
            <>
              <Link to="/login">로그인</Link>
              <Link to="/signup">회원가입</Link>
            </>
          )}
        </span>
      </nav>
      {/* 로그아웃 실패 안내는 메뉴 줄 밖에 둔다 */}
      {error && (
        <div className="nav-notice">
          <Notice kind="error">{error}</Notice>
        </div>
      )}
    </header>
  )
}
