import { useEffect, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import api from '../api'
import { useAuth } from '../auth/useAuth'
import { errorText } from '../errorMessage'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import VisaDisclaimer from '../components/VisaDisclaimer'
import VisaReviewFilters from '../components/VisaReviewFilters'
import { useVisaFilterOptions } from '../components/useVisaFilterOptions'
import { formatDate, formatDay } from '../formatDate'
import type { VisaReview } from '../types'

// 목록 요청 한 번의 결과. key(어떤 조건으로 요청했는지)를 같이 저장해 둔다
interface ListResult {
  key: string
  reviews: VisaReview[]
  error: string | null
}

// 비자 후기 목록 (읽기 전용). 거르기 값은 글 목록과 같이 주소 쿼리가 진짜 값이다
export default function VisaReviewListPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const schoolId = searchParams.get('schoolId') ?? ''
  const visaType = searchParams.get('visaType') ?? ''
  const applicationType = searchParams.get('applicationType') ?? ''
  // 요청 조건을 한 문자열로 묶은 것. 결과가 "지금 조건의 것"인지 비교하는 데 쓴다
  const queryKey = `${schoolId}|${visaType}|${applicationType}`

  const [result, setResult] = useState<ListResult | null>(null)
  const { schools, options, schoolsFailed, optionsFailed } = useVisaFilterOptions()

  // 주소의 필터가 바뀔 때마다 목록을 다시 불러온다
  useEffect(() => {
    // 조건이 또 바뀌거나 화면이 사라지면 true가 되어, 늦게 도착한 옛 응답이 최신 결과를 덮어쓰지 못한다
    let ignore = false
    // 빈 값은 보내지 않는다(조건 없음). 주소의 값 그대로 보내서 서버가 400/404로 판단하게 한다
    const params: Record<string, string> = {}
    if (schoolId) params.schoolId = schoolId
    if (visaType) params.visaType = visaType
    if (applicationType) params.applicationType = applicationType
    api
      .get<VisaReview[]>('/visa-reviews', { params })
      .then((res) => {
        if (!ignore) setResult({ key: queryKey, reviews: res.data, error: null })
      })
      .catch((e: unknown) => {
        if (ignore) return
        const error = errorText(
          e,
          {
            404: '존재하지 않는 학교입니다',
            400: '잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요',
          },
          '후기 목록을 불러오지 못했습니다',
        )
        setResult({ key: queryKey, reviews: [], error })
      })
    return () => {
      ignore = true
    }
  }, [schoolId, visaType, applicationType, queryKey])

  // 저장된 결과가 지금 조건의 것이 아니면 아직 불러오는 중이다
  const loading = result?.key !== queryKey
  const reviews = loading ? [] : (result?.reviews ?? [])
  const error = loading ? null : (result?.error ?? null)

  // 머리말: 고른 학교 이름 + " 비자 후기", 아니면 "비자 후기". 학교 목록을 기다리는 동안은 "비자 후기"만 보인다
  const schoolName = schoolId ? schools?.find((s) => String(s.id) === schoolId)?.name : undefined
  const title = schoolName ? `${schoolName} 비자 후기` : '비자 후기'

  return (
    <div>
      <h1 className="board-title">{title}</h1>
      <VisaDisclaimer />
      <div className="toolbar">
        <VisaReviewFilters
          schools={schools}
          options={options}
          schoolsFailed={schoolsFailed}
          optionsFailed={optionsFailed}
        />
        {/* 후기 쓰기 버튼은 항상 보이되, 비로그인이면 로그인 화면으로 보낸다 (글쓰기 버튼과 같은 규칙) */}
        <button type="button" className="btn btn-primary" onClick={() => navigate(user ? '/visa-reviews/new' : '/login')}>
          후기 쓰기
        </button>
      </div>
      {/* 어느 한쪽이라도 실패하면 안내 */}
      {(schoolsFailed || optionsFailed) &&<Notice kind="error">선택지 목록을 불러오지 못했습니다</Notice>}
      {/* 후기 목록: 글 목록처럼 판 하나 안의 행들 */}
      <div className="panel panel-flush">
        {loading && <Loading />}
        {error && (
          <div className="panel-state">
            <Notice kind="error">{error}</Notice>
          </div>
        )}
        {!loading && !error && reviews.length === 0 && <p className="panel-state">아직 후기가 없습니다</p>}
        <ul className="rows">
          {reviews.map((r) => (
            <li key={r.id} className="row">
              <div className="row-title">
                <span className={`badge badge-result-${r.result.code}`}>{r.result.name}</span>
                {/* 학교로 거른 화면에서는 모두 같은 학교라 뱃지를 생략한다 */}
                {!schoolId && <span className="badge badge-school">{r.school.name}</span>}
                <Link to={`/visa-reviews/${r.id}`} className="row-link">
                  {r.visaType.name} · {r.applicationType.name} · 처리 {r.processingDays}일
                </Link>
              </div>
              {/* 작성자 / 접수일 → 결과일 / 작성일 (본문 미리보기는 없다) */}
              <p className="meta">
                <span>{r.author?.nickname ?? '(작성자 없음)'}</span>
                <span>
                  {formatDay(r.receivedDate)} → {formatDay(r.resultDate)}
                </span>
                <span>{formatDate(r.createdAt)}</span>
              </p>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
