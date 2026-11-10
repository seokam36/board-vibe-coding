import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  // 단위 테스트 설정: 화면(브라우저) 없이 순수 함수만 검사하니 node 환경이면 충분하다.
  // include로 src 안의 *.test.ts만 돌린다 (나중에 e2e/*.spec.ts(Playwright)와 섞이지 않게).
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
  server: {
    // 개발 서버(5173)가 /api 로 시작하는 요청을 백엔드(8080)에 대신 전달한다.
    // 이유: 브라우저 입장에서 화면과 API가 같은 주소(5173)가 되어, 쿠키(refresh_token의 Path=/api/auth 포함)가
    // 그대로 오가고 axios가 CSRF 쿠키를 헤더로 자동 첨부한다. 배포 환경의 nginx(/api)와도 똑같은 모양이다.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        // changeOrigin은 false(기본값)로 둔다: Host 헤더가 localhost:5173 그대로라 백엔드가 "같은 출처 요청"으로 본다.
        // true여도 백엔드 CORS 기본 허용 주소가 5173이라 동작은 하지만, CORS 설정에 덜 기대는 쪽을 골랐다.
        changeOrigin: false,
        // 백엔드에는 /api 가 없으니 앞의 /api 를 떼고 전달한다 (nginx와 같은 동작)
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
