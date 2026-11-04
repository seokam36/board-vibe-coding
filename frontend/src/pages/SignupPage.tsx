import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import api from '../api'
import { NETWORK_ERROR_MESSAGE, getStatus, isNetworkError } from '../errorMessage'

export default function SignupPage() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const navigate = useNavigate()

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    try {
      // 입력 형식 검사는 프론트에서 하지 않고 서버의 400 응답을 그대로 쓴다
      await api.post('/auth/signup', { username, password })
      // 라우터 state로 "가입 완료" 문구를 로그인 화면에 넘긴다
      navigate('/login', { state: { message: '가입 완료! 로그인해 주세요' } })
    } catch (err: unknown) {
      const status = getStatus(err)
      if (isNetworkError(err)) {
        setError(NETWORK_ERROR_MESSAGE)
      } else if (status === 409) {
        setError('이미 사용 중인 아이디입니다')
      } else if (status === 400) {
        // 프론트에서 형식 검사는 안 하니, 서버 규칙을 문구로 알려 준다
        setError('아이디는 영문 소문자·숫자·_ 4~20자, 비밀번호는 8~64자로 입력해 주세요')
      } else if (status === 403) {
        // 보통 앱 시작 때 CSRF 쿠키(/csrf)를 못 받은 경우 (서버가 꺼져 있었다 등)
        setError('보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요')
      } else {
        setError('가입하지 못했습니다')
      }
      setSaving(false)
    }
  }

  return (
    <div>
      <h1>회원가입</h1>
      <p>HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요</p>
      <form onSubmit={handleSubmit}>
        <div>
          <label>
            아이디
            {/* 휴대폰이 첫 글자를 대문자로 바꾸면 서버 규칙(소문자만)에 걸리므로 자동 대문자·맞춤법 검사를 끈다 */}
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
              autoComplete="new-password"
            />
          </label>
        </div>
        {error && <p role="alert">{error}</p>}
        <button type="submit" disabled={saving}>
          {saving ? '가입 중...' : '가입'}
        </button>{' '}
        <Link to="/">목록으로</Link>
      </form>
    </div>
  )
}
