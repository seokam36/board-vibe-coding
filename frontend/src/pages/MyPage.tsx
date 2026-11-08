import { startTransition, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import type { AuthUser } from '../auth/useAuth'
import ReloginNotice from '../components/ReloginNotice'
import { describeError } from '../errorMessage'
import Notice from '../components/Notice'

// 보통 앱 시작 때 CSRF 쿠키(/csrf)를 못 받은 경우
const CSRF_MESSAGE = '보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요'

// 로그인 상태 비우기 + 화면 이동을 "한 번에" 반영한다.
// 따로 하면: 로그인 상태(user=null)가 먼저 그려지고, 아직 /me 인 화면을 RequireLogin이 보고 /login 으로 덮어써서
// 우리가 보낸 주소(/ 또는 안내 문구가 담긴 /login)가 사라진다. react-router는 주소 변경을 startTransition(느린 업데이트)으로 처리하기 때문.
// 같은 startTransition 안에 같이 넣으면 두 변경이 한 묶음으로 그려져 이 문제가 없다.
function clearAndGo(clear: () => void, go: () => void) {
  startTransition(() => {
    clear()
    go()
  })
}

// 내 정보 화면: 아이디·닉네임 표시 + 닉네임 변경 / 비밀번호 변경 / 회원 탈퇴
// 로그인이 풀리면(401) 다른 쓰기 화면처럼 입력 내용을 지키려고 화면을 그대로 두고, 각 폼이 안내 문구(ReloginNotice)를 보여 준다
export default function MyPage() {
  const { user } = useAuth()

  return (
    <div>
      <h1>내 정보</h1>
      {/* 아이디는 바꿀 수 없으므로 읽기 전용 글자로만 보여 준다. 로그인이 풀린 직후엔 user가 없을 수 있다 */}
      {user && (
        <div className="panel">
          <p>아이디: {user.username}</p>
          <p>닉네임: {user.nickname}</p>
        </div>
      )}
      <NicknameForm />
      <PasswordForm />
      <WithdrawForm />
      <Link to="/" className="btn">
        목록으로
      </Link>
    </div>
  )
}

// 닉네임 변경: 성공하면 서버가 돌려준 사용자 정보로 로그인 상태를 바로 갱신한다
function NicknameForm() {
  const { updateUser } = useAuth()
  const [nickname, setNickname] = useState('')
  const [saving, setSaving] = useState(false)
  const [needLogin, setNeedLogin] = useState(false) // 401: 자동 재발급까지 실패해 로그인이 풀림
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    setSuccess(null)
    setNeedLogin(false)
    try {
      // 형식·길이 검사는 서버 400에 맡긴다 (maxLength로 자르지 않는다)
      const res = await api.put<AuthUser>('/users/me/nickname', { nickname })
      updateUser(res.data) // 상단바와 위쪽 닉네임 표시가 새 닉네임으로 바뀐다
      setNickname('')
      setSuccess('닉네임이 바뀌었습니다')
    } catch (err: unknown) {
      const info = describeError(
        err,
        {
          409: '이미 사용 중인 닉네임입니다',
          400: '닉네임은 한글(완성형)·영문·숫자·_ 2~20자로 입력해 주세요 (공백·자음/모음만은 불가)',
          403: CSRF_MESSAGE,
        },
        '닉네임을 바꾸지 못했습니다',
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setError(info.message)
    }
    setSaving(false)
  }

  return (
    <form className="panel" onSubmit={handleSubmit}>
      <h2>닉네임 변경</h2>
      <div className="field">
        <label>
          새 닉네임
          <input
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            autoComplete="nickname"
            className="full-width"
          />
        </label>
        <small className="hint">한글(완성형)·영문·숫자·_ 2~20자, 공백·자음/모음만은 불가</small>
      </div>
      {needLogin && <ReloginNotice />}
      {error && <Notice kind="error">{error}</Notice>}
      {success && <Notice kind="success">{success}</Notice>}
      <button type="submit" className="btn btn-primary" disabled={saving}>
        {saving ? '변경 중...' : '닉네임 변경'}
      </button>
    </form>
  )
}

// 비밀번호 변경: 성공하면 서버가 모든 기기를 로그아웃시키므로 화면 상태도 비우고 로그인 화면으로 보낸다
function PasswordForm() {
  const { clearUser } = useAuth()
  const navigate = useNavigate()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [needLogin, setNeedLogin] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    setNeedLogin(false)
    try {
      await api.put('/users/me/password', { currentPassword, newPassword })
      // 서버가 이미 쿠키를 지웠으니 /auth/logout 은 부르지 않고 화면의 로그인 상태만 비운다
      clearAndGo(clearUser, () => navigate('/login', { state: { message: '비밀번호가 바뀌었습니다. 다시 로그인해 주세요' } }))
    } catch (err: unknown) {
      // 400: 서버는 "현재 비밀번호 틀림"도 400으로 준다(401이면 재발급을 시도하므로). 서버가 원인을 구분해 주지 않아 함께 안내한다
      // 500: 명세(13절): 비밀번호를 바꾼 뒤 Redis 실패로 500이 날 수 있어, 이미 바뀌었을 수도 있다
      const info = describeError(
        err,
        {
          400: '현재 비밀번호가 맞는지, 새 비밀번호가 8~64자이고 현재 비밀번호와 다른지 확인해 주세요',
          403: CSRF_MESSAGE,
          500: '비밀번호가 이미 바뀌었을 수 있습니다. 새 비밀번호로 다시 로그인해 보세요',
        },
        '비밀번호를 바꾸지 못했습니다',
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setError(info.message)
      setSaving(false) // 실패했을 때만 되살린다 (성공하면 화면이 바뀐다)
    }
  }

  return (
    <form className="panel" onSubmit={handleSubmit}>
      <h2>비밀번호 변경</h2>
      <Notice kind="info">HTTP라서 실제로 쓰는 비밀번호는 쓰지 마세요</Notice>
      <div className="field">
        <label>
          현재 비밀번호
          <input
            type="password"
            value={currentPassword}
            onChange={(e) => setCurrentPassword(e.target.value)}
            autoComplete="current-password"
            className="full-width"
          />
        </label>
      </div>
      <div className="field">
        <label>
          새 비밀번호
          <input
            type="password"
            value={newPassword}
            onChange={(e) => setNewPassword(e.target.value)}
            autoComplete="new-password"
            className="full-width"
          />
        </label>
        <small className="hint">8~64자</small>
      </div>
      {needLogin && <ReloginNotice />}
      {error && <Notice kind="error">{error}</Notice>}
      <button type="submit" className="btn btn-primary" disabled={saving}>
        {saving ? '변경 중...' : '비밀번호 변경'}
      </button>
    </form>
  )
}

// 회원 탈퇴: 비밀번호 + 확인 창. 성공하면 로그인 상태를 비우고 글 목록으로 보낸다
function WithdrawForm() {
  const { clearUser } = useAuth()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [saving, setSaving] = useState(false)
  const [needLogin, setNeedLogin] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    // 되돌릴 수 없는 작업이라 한 번 더 묻는다. 취소하면 요청을 보내지 않는다
    if (!window.confirm("탈퇴하면 되돌릴 수 없습니다. 쓴 글은 남고 작성자는 '(작성자 없음)'으로 바뀝니다. 탈퇴할까요?")) return
    setSaving(true)
    setError(null)
    setNeedLogin(false)
    try {
      // DELETE지만 본문이 있다: axios에서는 data 옵션으로 보낸다
      await api.delete('/users/me', { data: { password } })
      // 서버가 쿠키를 이미 지웠으므로 /auth/logout 은 부르지 않는다
      clearAndGo(clearUser, () => navigate('/', { state: { message: '탈퇴가 완료되었습니다' } }))
    } catch (err: unknown) {
      // 400: 서버는 틀린 비밀번호도 400으로 주고, 빈 값·너무 긴 값·잘못된 요청도 같은 400이라 원인을 단정하지 않는다
      // 500: 명세(14절): 회원을 지운 뒤 Redis 실패로 500이 날 수 있다. 다만 500만으로는 지워졌는지 알 수 없어
      // 로그인 상태는 비우지 않는다(안 지워졌는데 비우면 멀쩡한 로그인이 풀린 것처럼 보인다). 이미 지워졌다면 다음 요청이 401이 되어 자연히 풀린다
      const info = describeError(
        err,
        {
          400: '비밀번호를 입력했는지, 맞는지 확인해 주세요',
          403: CSRF_MESSAGE,
          500: '탈퇴가 이미 처리됐을 수 있습니다. 새로고침해서 로그인 상태를 확인해 주세요',
        },
        '탈퇴하지 못했습니다',
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setError(info.message)
      setSaving(false)
    }
  }

  return (
    <form className="panel panel-danger" onSubmit={handleSubmit}>
      <h2>회원 탈퇴</h2>
      <div className="field">
        <label>
          비밀번호
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
            className="full-width"
          />
        </label>
      </div>
      {needLogin && <ReloginNotice />}
      {error && <Notice kind="error">{error}</Notice>}
      <button type="submit" className="btn btn-danger" disabled={saving}>
        {saving ? '처리 중...' : '회원 탈퇴'}
      </button>
    </form>
  )
}
