import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import { useAuth } from '../auth/useAuth'
import { describeError } from '../errorMessage'
import Notice from '../components/Notice'

export default function LoginPage() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const { login } = useAuth()
  const navigate = useNavigate()
  // 회원가입 화면이 넘겨준 "가입 완료" 문구 (없으면 undefined)
  const location = useLocation()
  const notice = (location.state as { message?: string } | null)?.message

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    try {
      await login(username, password) // 성공하면 AuthContext의 로그인 상태가 갱신된다
      navigate('/') // 글 목록으로
    } catch (err: unknown) {
      // 여기서 401은 "로그인 풀림"이 아니라 아이디·비밀번호 오류라서 표에 직접 적는다
      // 403: 보통 앱 시작 때 CSRF 쿠키(/csrf)를 못 받은 경우 (서버가 꺼져 있었다 등)
      const info = describeError(
        err,
        {
          401: '아이디 또는 비밀번호가 틀렸습니다',
          400: '아이디와 비밀번호를 입력해 주세요',
          403: '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요',
        },
        '로그인하지 못했습니다',
      )
      if (info.kind === 'message') setError(info.message)
      setSaving(false)
    }
  }

  return (
    <div>
      <h1>로그인</h1>
      {notice && <Notice kind="info">{notice}</Notice>}
      <p>HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요</p>
      <form onSubmit={handleSubmit}>
        <div>
          <label>
            아이디
            {/* 휴대폰이 첫 글자를 대문자로 바꾸면 로그인이 실패하므로 자동 대문자·맞춤법 검사를 끈다 */}
            <input
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              autoCapitalize="none"
              spellCheck={false}
            />
          </label>
        </div>
        <div>
          <label>
            비밀번호
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </label>
        </div>
        {error && <Notice kind="error">{error}</Notice>}
        <button type="submit" disabled={saving}>
          {saving ? '로그인 중...' : '로그인'}
        </button>{' '}
        <Link to="/">목록으로</Link>
      </form>
    </div>
  )
}
