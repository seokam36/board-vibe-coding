import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import api from '../api'
import { describeError } from '../errorMessage'
import Notice from '../components/Notice'

export default function SignupPage() {
  const [username, setUsername] = useState('')
  const [nickname, setNickname] = useState('')
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
      await api.post('/auth/signup', { username, nickname, password })
      // 라우터 state로 "가입 완료" 문구를 로그인 화면에 넘긴다
      navigate('/login', { state: { message: '가입 완료! 로그인해 주세요' } })
    } catch (err: unknown) {
      // 409: 서버가 아이디·닉네임 중 어느 쪽인지 구분해 알려 주지 않는다
      // 400: 프론트에서 형식 검사는 안 하니, 서버 규칙을 문구로 알려 준다
      // 403: 보통 앱 시작 때 CSRF 쿠키(/csrf)를 못 받은 경우 (서버가 꺼져 있었다 등)
      const info = describeError(
        err,
        {
          409: '아이디 또는 닉네임이 이미 사용 중입니다',
          400: '아이디는 영문 소문자·숫자·_ 4~20자, 닉네임은 한글(완성형)·영문·숫자·_ 2~20자(공백·자음/모음만은 불가), 비밀번호는 8~64자로 입력해 주세요',
          403: '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요',
        },
        '가입하지 못했습니다',
      )
      // 이 화면은 쓰기 전 로그인이 필요 없어 401(needLogin)은 오지 않지만, 오면 기본 문구를 보여 준다
      setError(info.kind === 'message' ? info.message : '가입하지 못했습니다')
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
            닉네임
            {/* 글쓴이로 화면에 보이는 이름. 다른 칸처럼 형식·길이 검사는 서버(400)에 맡긴다
                (maxLength로 자르면 붙여넣은 긴 글이 몰래 잘려 의도와 다른 닉네임으로 가입될 수 있다) */}
            <input value={nickname} onChange={(e) => setNickname(e.target.value)} autoComplete="nickname" />
          </label>
          {/* 서버는 완성형 한글만 받으므로 "ㅋㅋ" 같은 자음·모음만은 안 된다고 미리 알려 준다 */}
          <small>한글(완성형)·영문·숫자·_ 2~20자, 공백·자음/모음만은 불가</small>
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
        {error && <Notice kind="error">{error}</Notice>}
        <button type="submit" disabled={saving}>
          {saving ? '가입 중...' : '가입'}
        </button>{' '}
        <Link to="/">목록으로</Link>
      </form>
    </div>
  )
}
