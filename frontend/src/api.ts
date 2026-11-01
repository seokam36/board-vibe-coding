import axios from 'axios'

// 백엔드 호출용 axios 인스턴스. 주소는 여기서만 정한다.
// ?? 대신 || 를 쓰는 이유: ?? 는 null/undefined만 걸러서,
// .env에 `VITE_API_BASE_URL=`처럼 빈 값을 두면 baseURL이 ""가 되어 버린다. ||는 빈 문자열도 기본값으로 바꾼다.
const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
})

export default api
