import { defineConfig, devices } from '@playwright/test'

// E2E(브라우저로 화면을 직접 눌러 보는) 테스트 설정.
// Vitest(단위 테스트, src/*.test.ts)와 섞이지 않게 e2e 폴더의 *.spec.ts만 돈다.
const isCI = !!process.env.CI

export default defineConfig({
  // 반드시 e2e로 지정: 기본값이면 src 안의 *.test.ts(Vitest 파일)까지 주워 간다
  testDir: 'e2e',
  // CI에서는 불안정한 테스트를 2번 다시 돌리고, 한 번에 하나씩만 돌려 안정성을 높인다
  retries: isCI ? 2 : 0,
  workers: isCI ? 1 : undefined,
  // CI에서 실수로 test.only가 남아 있으면 실패시킨다
  forbidOnly: isCI,
  reporter: isCI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5174',
    // 실패한 테스트를 다시 돌릴 때만 추적 기록을 남긴다
    trace: 'on-first-retry',
  },
  // 브라우저는 Chromium 하나만 쓴다 (학습용이라 가볍게)
  projects: [
    {
      name: 'chromium',
      // 이 PC 보안 정책이 Playwright가 내려받은 브라우저(chrome-headless-shell)를 막아서,
      // 로컬에서는 이미 깔린 정식 Edge(같은 Chromium 엔진)를 쓴다. CI는 내려받은 기본 Chromium.
      // 바꾸고 싶으면 PW_CHANNEL 환경변수(예: chrome)로 지정한다.
      use: { ...devices['Desktop Chrome'], channel: process.env.PW_CHANNEL || (isCI ? undefined : 'msedge') },
    },
  ],
  // 테스트 전에 Vite 개발 서버를 직접 띄운다.
  // 5173은 다른 프로젝트가 쓸 수 있어서 전용 포트 5174 + --strictPort(포트가 차 있으면 다른 번호로 안 넘어가고 실패)
  webServer: {
    command: 'npm run dev -- --port 5174 --strictPort',
    url: 'http://localhost:5174',
    // 로컬에서는 이미 떠 있는 서버를 재사용, CI에서는 항상 새로 띄운다
    reuseExistingServer: !isCI,
    timeout: 60_000,
    // .env.local에 다른 API 주소가 있어도 화면이 항상 /api 로 요청하게 고정한다 (그래야 page.route가 가로챈다)
    env: { VITE_API_BASE_URL: '/api' },
  },
})
