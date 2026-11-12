import api from './api'

// 사진 파일 주소 (docs/api.md 34번). <img src>는 axios를 거치지 않으니 axios와 같은 주소 접두어(baseURL)를 직접 붙인다.
// thumb=true면 썸네일(긴 변 ≤ 320), 아니면 본 사진(긴 변 ≤ 2048)
export function marketPhotoUrl(itemId: number, photoId: number, thumb = false): string {
  // api.ts가 baseURL에 항상 값을 넣으니(빈 값이면 '/api') 대체값은 필요 없다. 끝 슬래시는 떼서 "//"가 안 생기게 한다
  const base = (api.defaults.baseURL ?? '').replace(/\/+$/, '')
  return `${base}/market-items/${itemId}/photos/${photoId}${thumb ? '?size=thumb' : ''}`
}
