import axios from 'axios'

// 백엔드 호출용 axios 인스턴스. 주소는 여기서만 정한다.
// 기본값 '/api': 같은 출처(화면과 같은 주소)로 보내서, 로컬은 Vite 프록시가, 배포는 nginx가 백엔드로 전달한다.
// 같은 출처라서 withCredentials 없이도 쿠키가 자동으로 오가고,
// axios가 XSRF-TOKEN 쿠키를 X-XSRF-TOKEN 헤더로 자동 첨부한다 (withXSRFToken도 켤 필요 없음).
// 다른 출처로 바꾸면(예: http://localhost:8080) 쿠키·CSRF 헤더가 안 붙으니 주의.
// ?? 대신 || 를 쓰는 이유: ?? 는 null/undefined만 걸러서,
// .env에 `VITE_API_BASE_URL=`처럼 빈 값을 두면 baseURL이 ""가 되어 버린다. ||는 빈 문자열도 기본값으로 바꾼다.
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '/api',
})

export default api
