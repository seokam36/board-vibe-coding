import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import api from '../api'
import { errorText, ERROR_MESSAGES } from '../errorMessage'
import Loading from '../components/Loading'
import { useAuth } from '../auth/useAuth'
import MarketFilters from '../components/MarketFilters'
import MarketWriteGate from '../components/MarketWriteGate'
import Notice from '../components/Notice'
import { formatDate } from '../formatDate'
import { formatPrice } from '../marketFormat'
import type { MarketItem, School } from '../types'

// 목록 요청 한 번의 결과. key(어떤 조건으로 요청했는지)를 같이 저장해 둔다
interface ListResult {
  key: string
  items: MarketItem[]
  error: string | null
}

// 중고거래·나눔 목록 (읽기 전용). 거르기 값은 비자 후기 목록처럼 주소 쿼리가 진짜 값이다
export default function MarketListPage() {
  const [searchParams] = useSearchParams()
  const { user } = useAuth()
  const schoolId = searchParams.get('schoolId') ?? ''
  const tradeType = searchParams.get('tradeType') ?? ''
  const status = searchParams.get('status') ?? ''
  // 요청 조건을 한 문자열로 묶은 것. 결과가 "지금 조건의 것"인지 비교하는 데 쓴다
  const queryKey = `${schoolId}|${tradeType}|${status}`

  const [result, setResult] = useState<ListResult | null>(null)
  const [schools, setSchools] = useState<School[] | null>(null)
  const [schoolsFailed, setSchoolsFailed] = useState(false)

  // 학교 드롭다운 선택지: 화면이 나타날 때 한 번만 불러온다 (비자 후기 필터와 같은 GET /schools)
  useEffect(() => {
    let ignore = false
    api
      .get<School[]>('/schools')
      .then((res) => {
        if (!ignore) setSchools(res.data)
      })
      .catch(() => {
        if (!ignore) setSchoolsFailed(true)
      })
    return () => {
      ignore = true
    }
  }, [])

  // 주소의 필터가 바뀔 때마다 목록을 다시 불러온다
  useEffect(() => {
    // 조건이 또 바뀌거나 화면이 사라지면 true가 되어, 늦게 도착한 옛 응답이 최신 결과를 덮어쓰지 못한다
    let ignore = false
    // 빈 값은 보내지 않는다(조건 없음). 주소의 값 그대로 보내서 서버가 400/404로 판단하게 한다
    const params: Record<string, string> = {}
    if (schoolId) params.schoolId = schoolId
    if (tradeType) params.tradeType = tradeType
    if (status) params.status = status
    api
      .get<MarketItem[]>('/market-items', { params })
      .then((res) => {
        if (!ignore) setResult({ key: queryKey, items: res.data, error: null })
      })
      .catch((e: unknown) => {
        if (ignore) return
        const error = errorText(
          e,
          { 404: ERROR_MESSAGES.SCHOOL_NOT_FOUND, 400: '잘못된 조건입니다. 학교·판매/나눔·상태를 다시 골라 주세요' },
          '글 목록을 불러오지 못했습니다',
        )
        setResult({ key: queryKey, items: [], error })
      })
    return () => {
      ignore = true
    }
  }, [schoolId, tradeType, status, queryKey])

  // 저장된 결과가 지금 조건의 것이 아니면 아직 불러오는 중이다
  const loading = result?.key !== queryKey
  const items = loading ? [] : (result?.items ?? [])
  const error = loading ? null : (result?.error ?? null)

  return (
    <div>
      <h1 className="board-title">중고·나눔</h1>
      <div className="toolbar">
        <MarketFilters schools={schools} schoolsFailed={schoolsFailed} />
        {/* 글쓰기 버튼은 학교 인증을 마친 회원에게만 보인다 */}
        {user?.verifiedSchool && (
          <Link to="/market/new" className="btn btn-primary">
            글쓰기
          </Link>
        )}
      </div>
      {/* 비로그인·미인증이면 버튼 대신 안내 (인증·로그인 링크 포함) */}
      {!user?.verifiedSchool && <MarketWriteGate user={user} />}
      {schoolsFailed && <Notice kind="error">{ERROR_MESSAGES.REVIEW_OPTIONS_LOAD_FAILED}</Notice>}
      {/* 글 목록: 일반 글 목록처럼 판 하나 안의 행들 */}
      <div className="panel panel-flush">
        {loading && <Loading />}
        {error && (
          <div className="panel-state">
            <Notice kind="error">{error}</Notice>
          </div>
        )}
        {!loading && !error && items.length === 0 && <p className="panel-state">아직 올라온 글이 없습니다</p>}
        <ul className="rows">
          {items.map((it) => (
            <li key={it.id} className="row">
              <div className="row-title">
                <span className={`badge badge-${it.status.code}`}>{it.status.name}</span>
                <Link to={`/market/${it.id}`} className="row-link">
                  {it.title}
                </Link>
              </div>
              {/* 가격 / 학교 / 작성일 */}
              <p className="meta">
                <span className="market-price">{formatPrice(it)}</span>
                <span>{it.school.name}</span>
                <span>{formatDate(it.createdAt)}</span>
              </p>
            </li>
          ))}
        </ul>
      </div>
    </div>
  )
}
