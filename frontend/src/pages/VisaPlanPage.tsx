import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import { describeError, ERROR_MESSAGES } from '../errorMessage'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import ReloginNotice from '../components/ReloginNotice'
import { useConfirm } from '../components/confirmContext'
import { addYears, daysBetween, formatDDay } from '../dday'
import { formatDay, seoulToday } from '../formatDate'
import type { School, VisaPlan } from '../types'
import { BANNER_TEXT } from '../visa/visaBanner'
import { useVisaPlan } from '../visa/visaPlanContext'
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
  // 일정은 앱 보관소(VisaPlanProvider)가 한 번 불러와 둔 것을 쓴다. 저장·삭제 성공 때 보관소를 갱신하면 상단 띠도 바로 바뀐다
  const { status, plan: storePlan, errorMessage, needLogin: storeNeedLogin, loadedDay, setPlan, reload } = useVisaPlan()
  // 마지막으로 본 일정. 저장 중 로그인이 풀려 보관소가 비워져도 화면(폼)을 지키려고 기억해 둔다
  const [lastPlan, setLastPlan] = useState<VisaPlan | null>(null)
  if (status === 'ready' && storePlan && storePlan !== lastPlan) setLastPlan(storePlan) // 렌더 중 갱신(허용되는 패턴)
  // 보관소가 ready면 보관소 값, 아니면(로그인 풀림 등) 마지막으로 본/저장한 값. null = 아직 모름(불러오는 중이거나 실패)
  const plan = status === 'ready' ? storePlan : lastPlan
  const [writeOk, setWriteOk] = useState(false) // 가장 최근 저장·삭제가 성공했는지 (로그인이 풀려 있어도 성공했으면 재로그인 안내를 내린다)
  const loading = status === 'loading'
  const loadError = status === 'failed' ? errorMessage : null
  const [saveNeedLogin, setSaveNeedLogin] = useState(false) // 저장·삭제 중 401(재발급까지 실패)
  // 불러오기 중 401이거나 로그인이 풀려 보관소가 비워진(idle) 경우도 같은 안내
  const needLogin = saveNeedLogin || (status === 'idle' && !writeOk) || (status === 'failed' && storeNeedLogin)
  const [date, setDate] = useState('') // 날짜 입력칸 값 (저장에 실패해도 그대로 둔다)
  const [dateFilled, setDateFilled] = useState(false)
  if (!dateFilled && plan) {
    // 일정이 처음 확인되면 변경 폼에 현재 값을 한 번 채운다 (렌더 중 갱신)
    setDateFilled(true)
    setDate(plan.expiryDate ?? '')
  }
  const [saving, setSaving] = useState(false)
  const [saveError, setSaveError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const today = seoulToday()

  // 들어온 순간의 상태만 본다 (이후 상태 변화를 따라가면 첫 GET 실패 직후 또 GET이 나간다)
  // 실패 상태였거나 받은 날짜가 오늘과 다르면(자정 넘김) 한 번 다시 부르고, 불러오는 중이면 기다리기만
  const entry = useRef({ status, loadedDay, handled: false })
  useEffect(() => {
    const e = entry.current
    if (e.handled) return
    e.handled = true
    if (e.status === 'failed' || (e.status === 'ready' && e.loadedDay !== null && e.loadedDay !== seoulToday())) reload()
  }, [reload])

  // 저장·삭제 성공을 화면에 반영: 보관소(띠)와 로컬 기억을 같이 갱신한다. 로그인이 풀려 보관소가 idle이어도 화면은 바뀐다
  function commit(next: VisaPlan) {
    setPlan(next)
    setLastPlan(next)
    setWriteOk(true)
  }

  async function handleSave(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    setSaveError(null)
    setDeleteError(null) // 이전 삭제 실패 문구가 저장 성공 뒤에도 남지 않게
    setSaveNeedLogin(false)
    setWriteOk(false)
    if (!date) {
      setSaveError('만료일을 입력해 주세요') // 빈 값은 서버에 보내지 않는다
      return
    }
    setSaving(true)
    try {
      const res = await api.put<VisaPlan>('/users/me/visa-plan', { expiryDate: date })
      commit(res.data) // 응답으로 바로 갱신한다 (다시 GET 하지 않음)
    } catch (err: unknown) {
      const info = describeError(
        err,
        {
          400: '만료일을 확인해 주세요 (오늘부터 5년 이내의 날짜만 등록할 수 있어요)',
          403: ERROR_MESSAGES.VISA_SCHOOL_REQUIRED_RETRY,
        },
        ERROR_MESSAGES.SAVE_FAILED,
      )
      if (info.kind === 'needLogin') setSaveNeedLogin(true)
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
    setSaveNeedLogin(false)
    setWriteOk(false)
    try {
      await api.delete('/users/me/visa-plan')
      commit(EMPTY_PLAN) // 빈 상태로
      setDate('')
      setSaveError(null)
    } catch (err: unknown) {
      const info = describeError(err, { 404: ERROR_MESSAGES.alreadyDeleted('일정') }, ERROR_MESSAGES.DELETE_FAILED)
      if (info.kind === 'needLogin') setSaveNeedLogin(true)
      else {
        setDeleteError(info.message)
        if (info.status === 404) {
          // 이미 없는 일정이니 화면도 빈 상태로 맞춘다
          commit(EMPTY_PLAN)
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
            <Notice kind="info">{BANNER_TEXT.EXPIRED}</Notice>
          )}
          {plan.todos.length > 0 && (
            <>
              <h2 className="visa-todos-heading">할 일</h2>
              <ul className="rows visa-todos">
                {plan.todos.map((t) => {
                  // 할 일의 날짜는 마감이 아니라 "확인을 시작할 시점"이다. 상태를 네 가지 글자로도 알린다(색만으로 알리지 않음)
                  // 모르는 stage가 와도 단순 비교라 예외가 나지 않는다 (그냥 현재 단계 줄이 없을 뿐)
                  const isCurrent = t.code === plan.stage // 지금 들어와 있는 단계
                  const isPast = !isCurrent && t.daysLeft < 0 // 이미 지나간 단계 (EXPIRED면 전부)
                  const status = isCurrent
                    ? '지금 확인할 때'
                    : t.daysLeft > 0
                      ? `${t.daysLeft}일 뒤 시작`
                      : t.daysLeft === 0
                        ? '오늘 시작' // 서버 규칙상 도달 불가(0인 날은 stage가 그 code). 모르는 stage 대비
                        : '지난 단계'
                  return (
                    <li
                      key={t.code}
                      className={`row visa-todo${isCurrent ? ' visa-todo-current' : ''}${isPast ? ' visa-todo-past' : ''}`}
                    >
                      {/* 위 큰 D-n(만료일 기준)과 섞이지 않게, 이 줄은 "만료 N일 전부터"로 쓴다 */}
                      <span className="visa-todo-dday">만료 {daysBetween(t.dueDate, plan.expiryDate ?? '')}일 전부터</span>
                      {/* 날짜(부터 없음)와 상태 글자는 따로 읽혀도 뜻이 서게 쓰고, 간격(.meta의 gap)으로 나눈다 */}
                      <span className="meta">
                        <span>{formatDay(t.dueDate)}</span>
                        <span className={isCurrent ? 'visa-todo-status' : undefined}>{status}</span>
                      </span>
                      <span className="visa-todo-title">{t.title}</span>
                    </li>
                  )
                })}
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
