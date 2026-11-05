import { useState } from 'react'
import { Link } from 'react-router'
import { useAuth } from '../auth/useAuth'

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
    <nav style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
      <Link to="/">게시판</Link>
      <span>
        {user ? (
          <>
            {user.nickname} 님 <button onClick={handleLogout}>로그아웃</button>
          </>
        ) : (
          <>
            <Link to="/login">로그인</Link> <Link to="/signup">회원가입</Link>
          </>
        )}
      </span>
      {error && <p role="alert">{error}</p>}
    </nav>
  )
}
