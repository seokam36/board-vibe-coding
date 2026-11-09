import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import api from '../api'
import { errorText } from '../errorMessage'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import VisaDisclaimer from '../components/VisaDisclaimer'
import VisaReviewFilters from '../components/VisaReviewFilters'
import VisaReviewTabs from '../components/VisaReviewTabs'
import { useVisaFilterOptions } from '../components/useVisaFilterOptions'
import type { VisaReviewStat } from '../types'

// 통계 요청 한 번의 결과. key(어떤 조건으로 요청했는지)를 같이 저장해 둔다
interface StatsResult {
  key: string
  stats: VisaReviewStat[]
  error: string | null
}

// 비자 후기 통계 (읽기 전용). 거르기 값은 목록과 같이 주소 쿼리가 진짜 값이다
export default function VisaReviewStatsPage() {
  const [searchParams] = useSearchParams()
  const schoolId = searchParams.get('schoolId') ?? ''
  const visaType = searchParams.get('visaType') ?? ''
  const applicationType = searchParams.get('applicationType') ?? ''
  const queryKey = `${schoolId}|${visaType}|${applicationType}`

  const [result, setResult] = useState<StatsResult | null>(null)
  const { schools, options, schoolsFailed, optionsFailed } = useVisaFilterOptions()

  useEffect(() => {
    // 조건이 또 바뀌거나 화면이 사라지면 true: 늦게 도착한 옛 응답을 버린다
    let ignore = false
    const params: Record<string, string> = {}
    if (schoolId) params.schoolId = schoolId
    if (visaType) params.visaType = visaType
    if (applicationType) params.applicationType = applicationType
    api
      .get<VisaReviewStat[]>('/visa-reviews/stats', { params })
      .then((res) => {
        if (!ignore) setResult({ key: queryKey, stats: res.data, error: null })
      })
      .catch((e: unknown) => {
        if (ignore) return
        const error = errorText(
          e,
          {
            404: '존재하지 않는 학교입니다',
            400: '잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요',
          },
          '통계를 불러오지 못했습니다',
        )
        setResult({ key: queryKey, stats: [], error })
      })
    return () => {
      ignore = true
    }
  }, [schoolId, visaType, applicationType, queryKey])

  const loading = result?.key !== queryKey
  const stats = loading ? [] : (result?.stats ?? [])
  const error = loading ? null : (result?.error ?? null)

  // 머리말은 목록과 같은 규칙 (통계인지는 탭이 알려 준다)
  const schoolName = schoolId ? schools?.find((s) => String(s.id) === schoolId)?.name : undefined
  const title = schoolName ? `${schoolName} 비자 후기` : '비자 후기'

  return (
    <div>
      <h1 className="board-title">{title}</h1>
      <VisaDisclaimer />
      <VisaReviewTabs current="stats" />
      <div className="toolbar">
        <VisaReviewFilters
          schools={schools}
          options={options}
          schoolsFailed={schoolsFailed}
          optionsFailed={optionsFailed}
        />
      </div>
      {(schoolsFailed || optionsFailed) && <Notice kind="error">선택지 목록을 불러오지 못했습니다</Notice>}
      <div className="panel panel-flush">
        {loading && <Loading />}
        {error && (
          <div className="panel-state">
            <Notice kind="error">{error}</Notice>
          </div>
        )}
        {!loading && !error && stats.length === 0 && <p className="panel-state">아직 통계가 없습니다</p>}
        {stats.length > 0 && (
          <>
            {/* 표 wrapper만 가로 스크롤을 허용한다 (페이지는 가로로 안 밀린다) */}
            <div className="stats-table-wrap">
              <table className="stats-table">
                {/* 화면에는 안 보이고 스크린리더에만 읽히는 표 설명 */}
                <caption className="sr-only">학교·비자 종류·신청 종류별 후기 건수와 평균 처리일</caption>
                <thead>
                  <tr>
                    {/* 학교로 거른 화면에서는 학교 열을 생략한다 (목록의 학교 뱃지 생략과 같은 규칙) */}
                    {!schoolId && <th scope="col">학교</th>}
                    <th scope="col">비자 종류</th>
                    <th scope="col">신청 종류</th>
                    <th scope="col" className="num">건수</th>
                    <th scope="col" className="num">평균 처리일</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.map((s) => (
                    <tr key={`${s.school.id}|${s.visaType.code}|${s.applicationType.code}`}>
                      {!schoolId && <td>{s.school.name}</td>}
                      <td>{s.visaType.name}</td>
                      <td>{s.applicationType.name}</td>
                      <td className="num">{s.count}건</td>
                      {/* 서버가 소수 1자리로 주므로 19.0도 "19.0일" 그대로 (자릿수가 들쭉날쭉하면 읽기 어렵다) */}
                      <td className="num">{s.avgDays.toFixed(1)}일</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="stats-hint">건수가 적으면 평균이 크게 흔들릴 수 있어요. 승인·불허를 모두 포함한 값이에요</p>
          </>
        )}
      </div>
    </div>
  )
}
