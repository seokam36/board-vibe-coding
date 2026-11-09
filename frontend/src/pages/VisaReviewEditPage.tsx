import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { errorText } from '../errorMessage'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import VisaDisclaimer from '../components/VisaDisclaimer'
import VisaReviewForm from '../components/VisaReviewForm'
import { useVisaReviewOptions } from '../components/useVisaReviewOptions'
import { useAuth } from '../auth/useAuth'
import type { VisaReview, VisaReviewRequest } from '../types'

// 비자 후기 수정: 기존 후기로 채운 같은 폼
export default function VisaReviewEditPage() {
  const { id } = useParams()
  const safeId = encodeURIComponent(id ?? '') // 상세 화면과 같은 이유로 인코딩
  const [review, setReview] = useState<VisaReview | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const { options, optionsFailed } = useVisaReviewOptions()
  const { user } = useAuth()
  const isMine = !!user && review?.author?.id === user.id // 내 후기인지 (403 문구 분기용)
  const navigate = useNavigate()

  useEffect(() => {
    // 늦게 온 응답으로 상태를 바꾸지 않기 위한 플래그
    let ignore = false
    api
      .get<VisaReview>(`/visa-reviews/${safeId}`)
      .then((res) => {
        if (ignore) return
        setReview(res.data)
        setLoadError(null)
      })
      .catch((e: unknown) => {
        if (ignore) return
        setReview(null)
        setLoadError(errorText(e, { 400: '후기를 찾을 수 없습니다', 404: '후기를 찾을 수 없습니다' }, '후기를 불러오지 못했습니다'))
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [safeId])

  async function save(body: VisaReviewRequest) {
    await api.put(`/visa-reviews/${safeId}`, body)
    // replace: 저장한 수정 폼이 뒤로가기 기록에 남지 않게 한다
    navigate(`/visa-reviews/${safeId}`, { replace: true })
  }

  return (
    <div>
      <h1>비자 후기 수정</h1>
      <VisaDisclaimer />
      {loading && <Loading />}
      {loadError && <Notice kind="error">{loadError}</Notice>}
      {/* 지금 주소의 후기일 때만 폼을 보여 준다. 숫자로 비교해서 /visa-reviews/01 처럼 서버가 같은 후기로 보는 주소도 통과한다 */}
      {review && review.id === Number(id) && (
        <VisaReviewForm
          key={review.id}
          schoolName={review.school.name}
          options={options}
          optionsFailed={optionsFailed}
          initial={{
            visaType: review.visaType.code,
            applicationType: review.applicationType.code,
            receivedDate: review.receivedDate,
            resultDate: review.resultDate,
            result: review.result.code,
            content: review.content,
          }}
          current={{ visaType: review.visaType, applicationType: review.applicationType, result: review.result }}
          onSave={save}
          errorTable={{
            400: '날짜를 확인해 주세요 (접수일은 결과일과 같거나 앞서야 하고, 둘 다 오늘까지. 본문은 2000자까지)',
            // 본인 후기인데 403이면 CSRF 토큰 오류일 수 있다 (상세 화면 삭제와 같은 방식)
            403: isMine ? '저장하지 못했습니다. 새로고침 후 다시 시도해 주세요' : '본인 후기만 수정할 수 있습니다',
            404: '후기를 찾을 수 없습니다',
          }}
          cancelTo={`/visa-reviews/${safeId}`}
          cancelLabel="취소"
        />
      )}
      {loadError && (
        <Link to="/visa-reviews" className="btn">
          목록으로
        </Link>
      )}
    </div>
  )
}
