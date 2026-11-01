// 서버의 UTC 시각 문자열을 한국 시간 표기로 바꾼다. 화면에 시각을 보여줄 땐 반드시 이 함수를 쓴다.
export function formatDate(iso: string): string {
  return new Date(iso).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' })
}
