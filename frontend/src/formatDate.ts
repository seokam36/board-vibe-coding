// 서버의 UTC 시각 문자열을 한국 시간 표기로 바꾼다. 화면에 시각을 보여줄 땐 반드시 이 함수를 쓴다.
// 목록에서 훑어보기 쉽게 초는 빼고 "11월 8일 15:19"처럼 짧게. 올해가 아니면 연도를 앞에 붙인다.
export function formatDate(iso: string): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '' // 잘못된 날짜면 format이 RangeError를 내므로 빈 값
  const seoulYear = (d: Date) => new Intl.DateTimeFormat('ko-KR', { timeZone: 'Asia/Seoul', year: 'numeric' }).format(d)
  const sameYear = seoulYear(date) === seoulYear(new Date())
  return date.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul',
    ...(sameYear ? {} : { year: 'numeric' }),
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23', // 자정을 24:05가 아니라 00:05로
  })
}

// 날짜만 있는 "yyyy-MM-dd" 문자열을 "2026년 9월 1일"로 바꾼다.
// formatDate에 넣으면 UTC 자정으로 읽혀 시각이 붙으므로, Date를 거치지 않고 문자열을 직접 쪼갠다(시간대 영향 없음)
export function formatDay(day: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(day)
  if (!m) return '' // 모르는 모양이면 빈 값
  return `${Number(m[1])}년 ${Number(m[2])}월 ${Number(m[3])}일`
}
