import { useEffect, useState } from 'react'
import api from '../api'
import type { VisaReviewOptions } from '../types'

// 비자 후기 선택지(비자 종류·신청 종류·결과)만 불러온다. 작성·수정 폼과 거르기 화면이 함께 쓴다.
// 못 받았거나 실패하면 null이고, 실패하면 optionsFailed가 true
export function useVisaReviewOptions() {
  const [options, setOptions] = useState<VisaReviewOptions | null>(null)
  const [optionsFailed, setOptionsFailed] = useState(false)

  useEffect(() => {
    // 화면이 사라진 뒤 늦게 온 응답으로 상태를 바꾸지 않기 위한 플래그
    let ignore = false
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

  return { options, optionsFailed }
}
