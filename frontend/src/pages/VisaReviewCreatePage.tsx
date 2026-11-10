import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import api from '../api'
import { ERROR_MESSAGES } from '../errorMessage'
import VisaDisclaimer from '../components/VisaDisclaimer'
import VisaReviewForm from '../components/VisaReviewForm'
import { useVisaReviewOptions } from '../components/useVisaReviewOptions'
import { useAuth } from '../auth/useAuth'
import type { School, VisaReview, VisaReviewRequest } from '../types'

// 비자 후기 작성. 학교 인증을 마친 회원만 쓸 수 있다 (학교는 서버가 인증 학교로 정한다)
export default function VisaReviewCreatePage() {
  const { user } = useAuth()
  // 마지막으로 본 인증 학교. 저장 중 로그인이 풀려 user가 null이 돼도 폼(입력값)을 지키려고 기억해 둔다
  const [lastSchool, setLastSchool] = useState<School | null>(null)
  const currentSchool = user?.verifiedSchool ?? null
  if (currentSchool && currentSchool.id !== lastSchool?.id) setLastSchool(currentSchool) // 렌더 중 갱신(허용되는 패턴)
  // user가 있을 때만 미인증 판단. user가 null(세션 만료)이면 기억해 둔 학교로 폼을 유지 → ReloginNotice가 보인다
  const school = user ? currentSchool : lastSchool // null이면 미인증 → 폼 대신 안내
  const navigate = useNavigate()
  const { options, optionsFailed } = useVisaReviewOptions() // 선택지만 필요하다(학교는 서버가 정함)

  async function save(body: VisaReviewRequest) {
    const res = await api.post<VisaReview>('/visa-reviews', body)
    navigate(`/visa-reviews/${res.data.id}`) // 새 후기 상세로 이동
  }

  if (!school) {
    return (
      <div>
        <h1>비자 후기 쓰기</h1>
        <div className="panel">
          <p>비자 후기 작성은 학교 인증 후에 할 수 있어요</p>
          <Link to="/me" className="btn btn-primary">
            내 정보에서 학교 인증하기
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div>
      <h1>비자 후기 쓰기</h1>
      <VisaDisclaimer />
      <VisaReviewForm
        schoolName={school.name}
        options={options}
        optionsFailed={optionsFailed}
        onSave={save}
        errorTable={{
          400: ERROR_MESSAGES.REVIEW_FIELDS_INVALID,
          403: ERROR_MESSAGES.VISA_SCHOOL_REQUIRED_RETRY,
        }}
        cancelTo="/visa-reviews"
        cancelLabel="목록으로"
      />
    </div>
  )
}
