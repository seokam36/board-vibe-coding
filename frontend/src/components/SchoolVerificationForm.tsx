import { useEffect, useId, useState } from 'react'
import type { FormEvent } from 'react'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import type { AuthUser } from '../auth/useAuth'
import { CSRF_MESSAGE, describeError } from '../errorMessage'
import type { School } from '../types'
import Notice from './Notice'
import ReloginNotice from './ReloginNotice'

const SHOW_DOMAINS = 3 // 도메인 안내에 보여 줄 최대 개수
const RESEND_SECONDS = 60 // 서버의 재요청 쿨다운과 같다

// 내 정보 화면의 "학교 인증" 섹션.
// 인증 완료면 뱃지만, 미인증이면 [메일 입력 → 코드 보내기 → 6자리 코드 입력 → 확인] 두 단계를 보여 준다
export default function SchoolVerificationForm() {
  const { user, updateUser } = useAuth()
  const [step, setStep] = useState<'email' | 'code'>('email')
  const [email, setEmail] = useState('')
  const [code, setCode] = useState('')
  const [busy, setBusy] = useState(false) // 요청 중에는 버튼을 잠가 중복 클릭을 막는다
  const [cooldownEnd, setCooldownEnd] = useState(0) // "다시 보내기"가 풀리는 시각(ms)
  const [now, setNow] = useState(() => Date.now()) // 카운트다운 표시용 현재 시각
  const hintId = useId() // "확인" 버튼이 꺼진 이유 안내와 연결할 id
  const [needLogin, setNeedLogin] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [schools, setSchools] = useState<School[] | null>(null) // 허용 도메인 안내용. 못 받으면 안내만 생략

  // 허용 도메인 안내에 쓸 학교 목록을 한 번 받는다 (실패해도 인증 자체는 진행 가능)
  useEffect(() => {
    let ignore = false
    api
      .get<School[]>('/schools')
      .then((res) => {
        if (!ignore) setSchools(res.data)
      })
      .catch(() => {})
    return () => {
      ignore = true
    }
  }, [])

  // 재전송 카운트다운: 끝나는 시각에서 남은 초를 계산한다 (탭이 느려져도 어긋나지 않는다)
  const cooldown = Math.max(0, Math.ceil((cooldownEnd - now) / 1000))
  const counting = cooldown > 0
  useEffect(() => {
    if (!counting) return
    const timer = setInterval(() => setNow(Date.now()), 500)
    return () => clearInterval(timer)
  }, [counting])

  // 재전송 쿨다운 시작
  function startCooldown() {
    const t = Date.now()
    setNow(t)
    setCooldownEnd(t + RESEND_SECONDS * 1000)
  }

  // 이미 인증했으면 뱃지와 안내만 보여 준다
  if (user?.verifiedSchool) {
    return (
      <section className="panel">
        <h2>학교 인증</h2>
        <p>
          <span className="badge badge-verified">학교 인증 완료 · {user.verifiedSchool.name}</span>
        </p>
        <small className="hint">졸업 후에도 유지돼요</small>
      </section>
    )
  }

  // "@yonsei.ac.kr 등" 식의 허용 도메인 안내 (맨 앞 몇 개만)
  const domains = (schools ?? []).flatMap((s) => s.emailDomains ?? [])
  // 표시 개수보다 도메인이 더 많을 때만 "등"을 붙인다
  const domainHint =
    domains.length > 0 ? `${domains.slice(0, SHOW_DOMAINS).map((d) => `@${d}`).join(', ')}${domains.length > SHOW_DOMAINS ? ' 등' : ''}` : null
  const schoolNames = (schools ?? []).map((s) => s.name).join(', ')

  function resetNotices() {
    setError(null)
    setInfo(null)
    setNeedLogin(false)
  }

  // 이미 인증된 상태일 수 있으니(다른 기기 등) 내 정보를 다시 받아 화면을 맞춘다. 실패해도 조용히 넘어간다
  async function refreshUser() {
    try {
      const res = await api.get<AuthUser>('/auth/me')
      updateUser(res.data)
    } catch {
      // 안내 문구는 이미 보여 주고 있으니 여기서는 무시
    }
  }

  // 코드 보내기 / 다시 보내기 (같은 요청)
  async function sendCode(e?: FormEvent) {
    e?.preventDefault()
    resetNotices()
    setBusy(true)
    try {
      await api.post('/users/me/school-verification/code', { email: email.trim() })
      setStep('code')
      setCode('')
      startCooldown()
      setInfo('인증 코드를 보냈어요. 코드는 10분 동안 유효해요')
    } catch (err: unknown) {
      const res = describeError(
        err,
        {
          400: `학교 메일 주소를 확인해 주세요${schoolNames ? ` (지원 학교: ${schoolNames})` : ''}`,
          409: '이미 인증했거나 다른 계정이 인증에 쓴 메일이에요',
          429: '이미 보낸 코드가 있으면 메일의 코드를 입력해 주세요 (1분에 한 번, 하루 10번까지 보낼 수 있어요)',
          403: CSRF_MESSAGE,
          500: '메일을 보내지 못했어요. 잠시 후 다시 시도해 주세요',
        },
        '코드를 보내지 못했어요',
      )
      if (res.kind === 'needLogin') setNeedLogin(true)
      else if (res.status === 429) {
        // 기존 코드는 그대로 유효하니 코드 입력 단계로 넘기고, 연타 방지로 쿨다운도 건다
        setStep('code')
        setCode('')
        startCooldown()
        setInfo(res.message)
      } else {
        setError(res.message)
        // 이미 인증됐거나 메일이 겹친 경우: 회원 정보를 다시 받아 이미 인증됐다면 바로 완료 화면으로
        if (res.status === 409) await refreshUser()
      }
    }
    setBusy(false)
  }

  // 받은 코드 확인: 성공하면 서버가 돌려준 회원 정보로 바로 갱신해 상단바까지 즉시 바뀐다
  async function verify(e: FormEvent) {
    e.preventDefault()
    resetNotices()
    setBusy(true)
    try {
      const res = await api.post<AuthUser>('/users/me/school-verification', { code })
      updateUser(res.data) // verifiedSchool이 생기면 이 섹션은 위쪽 "인증 완료" 모양으로 바뀐다
    } catch (err: unknown) {
      const res = describeError(
        err,
        {
          400: '코드가 맞지 않아요. 다시 입력해 주세요',
          410: '코드가 만료됐거나 너무 많이 틀렸어요. 코드를 다시 받아 주세요',
          409: '이미 인증됐거나 다른 계정이 쓴 메일이에요',
          403: CSRF_MESSAGE,
        },
        '인증하지 못했어요',
      )
      if (res.kind === 'needLogin') setNeedLogin(true)
      else {
        // 코드가 폐기됐으니 입력칸을 비우고 메일 입력 단계로 돌려보낸다 (메일 주소는 남겨 둔다)
        if (res.status === 410) {
          setStep('email')
          setCode('')
          // 보내기 쿨다운이 남아 있으면 언제 다시 받을 수 있는지 덧붙인다
          setError(counting ? `${res.message} (${cooldown}초 뒤에 다시 받을 수 있어요)` : res.message)
        } else if (res.status === 409) {
          // 코드는 다시 받아야 한다. 이미 인증됐다면 회원 정보를 새로 받아 바로 완료 뱃지로
          setStep('email')
          setCode('')
          setError(res.message)
          await refreshUser()
        } else setError(res.message)
      }
    }
    setBusy(false)
  }

  return (
    <section className="panel">
      <h2>학교 인증</h2>
      <p>학교 메일로 인증하면 글쓰기·댓글·공감·신고를 할 수 있어요</p>
      {step === 'email' ? (
        <form onSubmit={sendCode}>
          <div className="field">
            <label>
              학교 메일
              <input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                autoComplete="email"
                className="full-width"
              />
            </label>
            {domainHint && <small className="hint">{domainHint} 학교 메일만 쓸 수 있어요</small>}
          </div>
          {needLogin && <ReloginNotice />}
          {error && <Notice kind="error">{error}</Notice>}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={busy || counting}>
              {busy ? '보내는 중...' : counting ? `코드 보내기 (${cooldown}초)` : '코드 보내기'}
            </button>
            {/* 새로고침 등으로 이 단계로 돌아와도 이미 받은 코드를 입력할 수 있게 */}
            <button
              type="button"
              className="btn"
              onClick={() => {
                resetNotices()
                setStep('code')
              }}
              disabled={busy}
            >
              이미 코드를 받았어요
            </button>
          </div>
        </form>
      ) : (
        <form onSubmit={verify}>
          <div className="field">
            <label>
              인증 코드 (6자리)
              <input
                value={code}
                // 숫자만 받고 6자리까지만 (붙여넣기도 걸러진다)
                onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric"
                autoComplete="one-time-code"
                maxLength={6}
                className="full-width"
              />
            </label>
            <small className="hint">메일이 안 보이면 스팸함도 확인해 주세요</small>
            {/* 확인 버튼이 꺼져 있는 이유 (스크린리더는 aria-describedby로 읽는다) */}
            {code.length !== 6 && (
              <small id={hintId} className="hint">
                6자리를 입력하면 확인할 수 있어요
              </small>
            )}
          </div>
          {needLogin && <ReloginNotice />}
          {info && <Notice kind="info">{info}</Notice>}
          {error && <Notice kind="error">{error}</Notice>}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={busy || code.length !== 6}
              aria-describedby={code.length !== 6 ? hintId : undefined}
            >
              {busy ? '확인 중...' : '확인'}
            </button>
            <button type="button" className="btn" onClick={() => sendCode()} disabled={busy || counting}>
              {counting ? `다시 보내기 (${cooldown}초)` : '다시 보내기'}
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => {
                resetNotices()
                setStep('email')
              }}
              disabled={busy}
            >
              메일 바꾸기
            </button>
          </div>
        </form>
      )}
    </section>
  )
}
