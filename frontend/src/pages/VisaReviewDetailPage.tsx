import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import api from '../api'
import { errorText } from '../errorMessage'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import VisaDisclaimer from '../components/VisaDisclaimer'
import { formatDate, formatDay } from '../formatDate'
import type { VisaReview } from '../types'

// 비자 후기 상세 (읽기 전용). 수정·삭제 버튼은 다음 단계(S11-e-2)에서 붙인다
export default function VisaReviewDetailPage() {
  // useParams: 주소의 :id 부분. 항상 문자열(또는 undefined)이다
  const { id } = useParams()
  const [review, setReview] = useState<VisaReview | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    // 응답이 오기 전에 화면이 사라지거나 id가 바뀌면 늦게 온 응답을 버리기 위한 플래그
    let ignore = false
    api
      // encodeURIComponent: '?' 같은 문자가 경로에 섞여 목록 API로 바뀌는 것을 막는다
      .get<VisaReview>(`/visa-reviews/${encodeURIComponent(id ?? '')}`)
      .then((res) => {
        if (ignore) return
        setReview(res.data)
        setError(null)
      })
      .catch((e: unknown) => {
        if (ignore) return
        setReview(null)
        setError(errorText(e, { 400: '후기를 찾을 수 없습니다', 404: '후기를 찾을 수 없습니다' }, '후기를 불러오지 못했습니다'))
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [id])

  return (
    <div>
      <VisaDisclaimer />
      {loading && <Loading />}
      {error && <Notice kind="error">{error}</Notice>}
      {review && (
        <article className="panel">
          {/* 결과·학교만 (비자·신청 종류는 아래 제목에 있다) */}
          <div className="badges">
            <span className={`badge badge-result-${review.result.code}`}>{review.result.name}</span>
            <span className="badge badge-school">{review.school.name}</span>
          </div>
          <h1 className="post-title">
            {review.visaType.name} · {review.applicationType.name}
          </h1>
          <p className="meta">
            <span>{review.author?.nickname ?? '(작성자 없음)'}</span>
            <span>{formatDate(review.createdAt)}</span>
          </p>
          {/* 날짜 블록: 접수일 → 결과일 (처리 일수) */}
          <p className="visa-dates">
            접수 {formatDay(review.receivedDate)} → 결과 {formatDay(review.resultDate)} ({review.processingDays}일)
          </p>
          {/* pre-wrap: 줄바꿈·공백을 그대로 보여준다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
          {review.content ? (
            <p className="pre-wrap post-body">{review.content}</p>
          ) : (
            <p className="post-body visa-empty">남긴 메모가 없어요</p>
          )}
        </article>
      )}
      <p>
        <Link to="/visa-reviews" className="btn">
          목록으로
        </Link>
      </p>
    </div>
  )
}
