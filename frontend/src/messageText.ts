// 쪽지 화면들이 같이 쓰는 글자

// 상대가 탈퇴해 partner가 null일 때 보여 주는 이름 (쪽지함·대화 화면 공통)
export const WITHDRAWN_PARTNER = '탈퇴한 회원'

// 대화 화면에서 최근 200통만 보여 줄 때(hasMore) 목록 위에 띄우는 안내 (회원용·관리자용)
export const MESSAGES_TRUNCATED = '이전 쪽지는 표시되지 않아요'
// 서버 RecentMessages.LIMIT과 같아야 한다 (docs/api.md 참고)
export const RECENT_MESSAGE_LIMIT = 200
export const ADMIN_MESSAGES_TRUNCATED = `최근 ${RECENT_MESSAGE_LIMIT}통만 보여요. ${MESSAGES_TRUNCATED}`

// 쪽지함 줄 배지에 보이는 숫자: 1~99는 그대로, 100 이상은 "99+". 0 이하(안 읽은 게 없음)는 빈 문자열 = 배지를 안 그린다
export function formatUnreadBadge(count: number): string {
  if (count <= 0) return ''
  return count >= 100 ? '99+' : String(count)
}
