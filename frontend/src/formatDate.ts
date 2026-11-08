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
