import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import { describeError, ERROR_MESSAGES } from '../errorMessage'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import ReloginNotice from '../components/ReloginNotice'
import { useConfirm } from '../components/confirmContext'
import { addYears, formatDDay } from '../dday'
import { formatDay, seoulToday } from '../formatDate'
import type { School, VisaPlan } from '../types'
import { HIKOREA_URL } from '../visaOfficialLink'

// 일정이 없는 상태. 서버도 일정이 없으면 이 모양으로 준다
const EMPTY_PLAN: VisaPlan = { expiryDate: null, daysLeft: null, stage: null, todos: [] }

// 모든 상태에서 보이는 한 줄 안내 (개인별 판단은 하지 않는다는 선)
function PlanGuide() {
  return (
    <Notice kind="info">
      일반 안내이며 개인 상황은{' '}
      <a href={HIKOREA_URL} target="_blank" rel="noopener noreferrer">
        하이코리아
      </a>
      나 관할 출입국·외국인청에서 확인하세요
    </Notice>
  )
}

// 내 비자 일정 화면. 학교 인증 회원만 쓸 수 있다 (로그인 여부는 App.tsx의 RequireLogin이 먼저 거른다)
export default function VisaPlanPage() {
  const { user } = useAuth()
  // 마지막으로 본 인증 학교. 저장 중 로그인이 풀려 user가 null이 돼도 화면(입력값)을 지키려고 기억해 둔다 (비자 후기 작성 화면과 같은 방식)
  const [lastSchool, setLastSchool] = useState<School | null>(null)
  const currentSchool = user?.verifiedSchool ?? null
  if (currentSchool && currentSchool.id !== lastSchool?.id) setLastSchool(currentSchool) // 렌더 중 갱신(허용되는 패턴)
  const school = user ? currentSchool : lastSchool

  // 미인증이면 일정 조회(GET)를 아예 부르지 않는다: 어차피 쓸 수 없어서 안내만 보여 주는 쪽이 단순하다
  if (!school) {
    return (
      <div>
        <h1>비자 일정</h1>
        <div className="panel">
          <p>비자 일정은 학교 인증 후에 등록할 수 있어요</p>
          <Link to="/me" className="btn btn-primary">
            내 정보에서 학교 인증하기
          </Link>
        </div>
        <PlanGuide />
      </div>
    )
  }
  return <VisaPlanView />
}

// 인증 회원용 본체: 불러오기 → (없음 | 있음) 분기
function VisaPlanView() {
  const confirm = useConfirm() // 삭제 전 확인 창
  const [plan, setPlan] = useState<VisaPlan | null>(null) // null = 아직 모름(불러오는 중이거나 실패)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [needLogin, setNeedLogin] = useState(false) // 불러오기·저장·삭제 중 401(재발급까지 실패)
  const [date, setDate] = useState('') // 날짜 입력칸 값 (저장에 실패해도 그대로 둔다)
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const today = seoulToday()

  useEffect(() => {
    let ignore = false // 화면이 사라진 뒤 늦게 온 응답을 버리기 위한 플래그
    api
      .get<VisaPlan>('/users/me/visa-plan')
      .then((res) => {
        if (ignore) return
        setPlan(res.data)
        setDate(res.data.expiryDate ?? '') // 일정이 있으면 변경 폼에 현재 값을 채운다
      })
      .catch((e: unknown) => {
        if (ignore) return
        const info = describeError(e, {}, '일정을 불러오지 못했습니다')
        if (info.kind === 'needLogin') setNeedLogin(true)
        else setLoadError(info.message)
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [])

  async function handleSave(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setSaveError(null)
    setDeleteError(null) // 이전 삭제 실패 문구가 저장 성공 뒤에도 남지 않게
    setNeedLogin(false)
    if (!date) {
      setSaveError('만료일을 입력해 주세요') // 빈 값은 서버에 보내지 않는다
      return
    }
    setSaving(true)
    try {
      const res = await api.put<VisaPlan>('/users/me/visa-plan', { expiryDate: date })
      setPlan(res.data) // 응답으로 바로 화면을 바꾼다 (다시 GET 하지 않음)
    } catch (err: unknown) {
      const info = describeError(
        err,
        {
          400: '만료일을 확인해 주세요 (오늘부터 5년 이내의 날짜만 등록할 수 있어요)',
          403: ERROR_MESSAGES.VISA_SCHOOL_REQUIRED_RETRY,
        },
        ERROR_MESSAGES.SAVE_FAILED,
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setSaveError(info.message) // 입력값(date)은 그대로 남는다
    } finally {
      setSaving(false)
    }
  }

  async function handleDelete() {
    // 되돌릴 수 없는 동작이라 먼저 확인한다. 취소하면 아무것도 하지 않는다
    if (!(await confirm({ message: '등록한 비자 일정을 삭제할까요?', confirmLabel: '삭제', danger: true }))) return
    setDeleting(true)
    setDeleteError(null)
    setNeedLogin(false)
    try {
      await api.delete('/users/me/visa-plan')
      setPlan(EMPTY_PLAN) // 빈 상태로
      setDate('')
      setSaveError(null)
    } catch (err: unknown) {
      const info = describeError(err, { 404: ERROR_MESSAGES.alreadyDeleted('일정') }, ERROR_MESSAGES.DELETE_FAILED)
      if (info.kind === 'needLogin') setNeedLogin(true)
      else {
        setDeleteError(info.message)
        if (info.status === 404) {
          // 이미 없는 일정이니 화면도 빈 상태로 맞춘다
          setPlan(EMPTY_PLAN)
          setDate('')
        }
      }
    } finally {
      setDeleting(false)
    }
  }

  const hasPlan = plan !== null && plan.expiryDate !== null && plan.daysLeft !== null

  return (
    <div>
      <h1>비자 일정</h1>
      <PlanGuide />
      {loading && <Loading />}
      {loadError && <Notice kind="error">{loadError}</Notice>}
      {/* 불러오기에 실패하면 일정이 있는지 모르니 폼을 숨긴다 (모르고 덮어쓰지 않게) */}
      {plan && hasPlan && (
        <section className="panel" aria-label="내 비자 일정">
          {/* 큰 D-n: 단계(stage)는 색 강조에만 쓴다 */}
          <p className={`visa-dday visa-dday-${plan.stage ?? 'NONE'}`}>{formatDDay(plan.daysLeft ?? 0)}</p>
          <p className="meta">체류 만료일 {formatDay(plan.expiryDate ?? '')}</p>
          {plan.stage === 'EXPIRED' && (
            <Notice kind="info">등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요</Notice>
          )}
          {plan.todos.length > 0 && (
            <>
              <h2 className="visa-todos-heading">할 일</h2>
              <ul className="rows visa-todos">
                {plan.todos.map((t) => (
                  <li key={t.code} className={`row visa-todo${t.daysLeft < 0 ? ' visa-todo-past' : ''}`}>
                    <span className="visa-todo-dday">{formatDDay(t.daysLeft)}</span>
                    {/* 날짜와 "지남"은 가운뎃점 대신 간격(.meta의 gap)으로 나눈다 */}
                    <span className="meta">
                      <span>{formatDay(t.dueDate)}</span>
                      {t.daysLeft < 0 && <span>지남</span>}
                    </span>
                    <span className="visa-todo-title">{t.title}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
      {plan && !hasPlan && (
        <div className="panel">
          <p className="visa-empty">등록된 일정 없음</p>
        </div>
      )}
      {plan && (
        <form className="panel" onSubmit={handleSave}>
          <label>
            체류 만료일
            {/* min/max는 힌트일 뿐 최종 판단은 서버(서울 오늘 ~ 오늘+5년)가 한다 */}
            <input type="date" value={date} min={today} max={addYears(today, 5)} onChange={(e) => setDate(e.target.value)} />
          </label>
          {saveError && <Notice kind="error">{saveError}</Notice>}
          <div className="actions">
            <button type="submit" className="btn btn-primary" disabled={saving || deleting}>
              {saving ? '저장 중...' : hasPlan ? '만료일 변경' : '저장'}
            </button>
            {hasPlan && (
              <button type="button" className="btn btn-danger" onClick={handleDelete} disabled={saving || deleting}>
                {deleting ? '삭제 중...' : '삭제'}
              </button>
            )}
          </div>
        </form>
      )}
      {/* 삭제 실패·로그인 풀림 안내는 폼 밖에 둔다 */}
      {deleteError && <Notice kind="error">{deleteError}</Notice>}
      {needLogin && <ReloginNotice />}
    </div>
  )
}
