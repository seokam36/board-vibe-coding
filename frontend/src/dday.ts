// 비자 일정 화면용 날짜 계산 모음 (서버가 준 daysLeft를 사람이 읽는 글자로 바꾸는 순수 함수들)

// 남은 일수 → 화면 글자. 111 → "D-111", 0 → "D-Day", -3 → "D+3" (지난 날은 + 로)
export function formatDDay(daysLeft: number): string {
  if (daysLeft === 0) return 'D-Day'
  return daysLeft > 0 ? `D-${daysLeft}` : `D+${-daysLeft}`
}

// yyyy-MM-dd 에 연도를 더한다. 2월 29일이 없는 해로 넘어가면 그 달 마지막 날(2월 28일)로 맞춘다.
// 날짜 입력칸의 max 힌트용이다. 진짜 범위 검사는 서버가 한다.
export function addYears(day: string, years: number): string {
  const [y, m, d] = day.split('-').map(Number)
  const date = new Date(Date.UTC(y + years, m - 1, d))
  if (date.getUTCMonth() !== m - 1) date.setTime(Date.UTC(y + years, m, 0)) // 넘쳤으면 그 달 말일로
  return date.toISOString().slice(0, 10)
}
