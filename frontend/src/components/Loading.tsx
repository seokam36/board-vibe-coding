import { LOADING_TEXT } from './loadingText'

// 불러오는 중 표시. prefix를 주면 "댓글 불러오는 중..."처럼 앞에 붙는다
export default function Loading({ prefix }: { prefix?: string }) {
  return <p className="loading">{prefix ? `${prefix} ${LOADING_TEXT}` : LOADING_TEXT}</p>
}
