import { useEffect, useState } from 'react'
import api from '../api'
import type { School, VisaReviewOptions } from '../types'

// 비자 후기 거르기용 목록(학교·비자/신청 종류)을 화면이 나타날 때 한 번 불러온다.
// 못 받았거나 실패하면 null로 두고, 실패하면 schoolsFailed/optionsFailed가 true (목록 본문은 계속 보여 준다)
export function useVisaFilterOptions() {
  const [schools, setSchools] = useState<School[] | null>(null)
  const [options, setOptions] = useState<VisaReviewOptions | null>(null)
  // 두 요청의 실패를 따로 기억한다 (한쪽 실패가 다른 쪽 "불러오는 중"을 끄지 않게)
  const [schoolsFailed, setSchoolsFailed] = useState(false)
  const [optionsFailed, setOptionsFailed] = useState(false)

  useEffect(() => {
    // 화면이 사라진 뒤 늦게 온 응답으로 상태를 바꾸지 않기 위한 플래그
    let ignore = false
    api
      .get<School[]>('/schools')
      .then((res) => {
        if (!ignore) setSchools(res.data)
      })
      .catch(() => {
        if (!ignore) setSchoolsFailed(true)
      })
    api
      .get<VisaReviewOptions>('/visa-review-options')
      .then((res) => {
        if (!ignore) setOptions(res.data)
      })
      .catch(() => {
        if (!ignore) setOptionsFailed(true)
      })
    return () => {
      ignore = true
    }
  }, [])

  return { schools, options, schoolsFailed, optionsFailed }
}
