import type { ReactNode } from 'react'

// 화면 안내 문구 한 곳. error는 바로 읽어 주는 경고(alert), success·info는 정중하게 알리는 상태(status)
// id: aria-describedby로 다른 요소(비활성 버튼 등)와 연결할 때만 쓴다
export default function Notice({ kind, children, id }: { kind: 'error' | 'success' | 'info'; children: ReactNode; id?: string }) {
  // className은 디자인 단계에서 종류별 색을 CSS만으로 입히기 위한 이름표(지금은 규칙이 없어 모양 변화 없음)
  if (kind === 'error') return <p id={id} role="alert" className="notice notice-error">{children}</p>
  return <p id={id} role="status" className={`notice notice-${kind}`}>{children}</p>
}
