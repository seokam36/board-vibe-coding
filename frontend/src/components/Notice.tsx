import type { ReactNode } from 'react'

// 화면 안내 문구 한 곳. error는 바로 읽어 주는 경고(alert), success·info는 정중하게 알리는 상태(status)
export default function Notice({ kind, children }: { kind: 'error' | 'success' | 'info'; children: ReactNode }) {
  // className은 디자인 단계에서 종류별 색을 CSS만으로 입히기 위한 이름표(지금은 규칙이 없어 모양 변화 없음)
  if (kind === 'error') return <p role="alert" className="notice notice-error">{children}</p>
  return <p role="status" className={`notice notice-${kind}`}>{children}</p>
}
