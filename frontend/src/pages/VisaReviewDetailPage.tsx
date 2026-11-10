import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { describeError, errorText, ERROR_MESSAGES } from '../errorMessage'
import { isAdmin } from '../auth/isAdmin'
import { useAuth } from '../auth/useAuth'
import ReloginNotice from '../components/ReloginNotice'
import { useConfirm } from '../components/confirmContext'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import VisaDisclaimer from '../components/VisaDisclaimer'
import { formatDate, formatDay } from '../formatDate'
import type { VisaReview } from '../types'

// 비자 후기 상세. 수정(본인)·삭제(본인 또는 관리자) 버튼이 있다
export default function VisaReviewDetailPage() {
  // useParams: 주소의 :id 부분. 항상 문자열(또는 undefined)이다
  const { id } = useParams()
  const [review, setReview] = useState<VisaReview | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // 삭제 요청 중 / 삭제 실패 문구 / 삭제 중 401(재발급 실패) 로그인 안내
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [needLogin, setNeedLogin] = useState(false)
  const navigate = useNavigate()
  const { user } = useAuth()
  const confirm = useConfirm() // 삭제 전 확인 창
  const isMine = !!user && review?.author?.id === user.id // 내 후기인지

  useEffect(() => {
    // 응답이 오기 전에 화면이 사라지거나 id가 바뀌면 늦게 온 응답을 버리기 위한 플래그
    let ignore = false
    // 지금은 상세→상세 이동이 없어 매번 새로 마운트된다 (글 상세와 같다).
    // id만 바뀌는 이동이 생기면 loading/error/review 초기화를 추가해야 한다
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
        setError(
          errorText(
            e,
            { 400: ERROR_MESSAGES.REVIEW_NOT_FOUND, 404: ERROR_MESSAGES.REVIEW_NOT_FOUND },
            ERROR_MESSAGES.REVIEW_LOAD_FAILED,
          ),
        )
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [id])

  async function handleDelete() {
    // 되돌릴 수 없는 동작이라 먼저 확인한다. 취소하면 아무것도 하지 않는다
    if (!(await confirm({ message: '이 후기를 삭제할까요?', confirmLabel: '삭제', danger: true }))) return
    setDeleting(true)
    setDeleteError(null)
    setNeedLogin(false)
    try {
      await api.delete(`/visa-reviews/${encodeURIComponent(id ?? '')}`)
      // replace: 삭제된 후기의 상세 화면이 뒤로가기 기록에 남지 않게 한다
      navigate('/visa-reviews', { replace: true })
    } catch (err: unknown) {
      // 403: 남의 후기이거나, 본인 후기인데 CSRF 토큰 오류. 본인이면 새로고침을 안내한다
      const info = describeError(
        err,
        {
          403: isMine ? ERROR_MESSAGES.DELETE_RETRY_AFTER_RELOAD : ERROR_MESSAGES.DELETE_FORBIDDEN,
          404: ERROR_MESSAGES.alreadyDeleted('후기'),
        },
        ERROR_MESSAGES.DELETE_FAILED,
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setDeleteError(info.message)
      setDeleting(false) // 실패했을 때만 버튼을 되살린다 (성공하면 페이지가 바뀐다)
    }
  }

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
      {/* 수정은 내 후기만, 삭제는 내 후기 또는 관리자. 숨기는 건 편의일 뿐, 진짜 보호는 서버의 403이다 */}
      {review && user && (isMine || isAdmin(user)) && (
        <div className="actions">
          {isMine && (
            <Link to={`/visa-reviews/${encodeURIComponent(id ?? '')}/edit`} className="btn btn-sm">
              수정
            </Link>
          )}
          <button type="button" className="btn btn-sm btn-danger" onClick={handleDelete} disabled={deleting}>
            {deleting ? '삭제 중...' : '삭제'}
          </button>
        </div>
      )}
      {/* 삭제 실패 안내는 버튼 영역 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게) */}
      {deleteError && <Notice kind="error">{deleteError}</Notice>}
      {needLogin && <ReloginNotice />}
      <p>
        <Link to="/visa-reviews" className="btn">
          목록으로
        </Link>
      </p>
    </div>
  )
}
