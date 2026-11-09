import { useEffect, useState } from 'react'
import api from '../api'
import { useVisaReviewOptions } from './useVisaReviewOptions'
import type { School } from '../types'

// 비자 후기 거르기용 목록(학교 + 비자/신청 종류)을 화면이 나타날 때 한 번 불러온다.
// 학교는 여기서, 선택지는 useVisaReviewOptions가 불러온다. 실패하면 schoolsFailed/optionsFailed가 true (목록 본문은 계속 보여 준다)
export function useVisaFilterOptions() {
  const [schools, setSchools] = useState<School[] | null>(null)
  const [schoolsFailed, setSchoolsFailed] = useState(false)
  const { options, optionsFailed } = useVisaReviewOptions()

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
    return () => {
      ignore = true
    }
  }, [])

  return { schools, options, schoolsFailed, optionsFailed }
}
