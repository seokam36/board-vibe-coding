import { defineConfig, devices } from '@playwright/test'

// Flutter 웹 e2e 설정. 옛 React e2e(S16-p에서 삭제)와 같은 방식이지만 서버가 다르다:
// 개발 서버가 아니라 "미리 빌드해 둔 build/web"을 serve.mjs로 보여 준다 (먼저 flutter build web --web-content-hash).
const isCI = !!process.env.CI

export default defineConfig({
  testDir: '.',
  testMatch: '*.spec.ts', // node_modules 안의 파일은 testDir 기본 규칙으로 제외된다
  // Flutter 웹은 첫 로딩(엔진 내려받기·초기화)이 느려서 넉넉히 잡는다.
  // 테스트 제한시간은 첫 로딩 대기(mockApi.ts의 FIRST_LOAD 30초)보다 길어야 하고, CI 러너는 느려서 2배로 둔다.
  timeout: isCI ? 120_000 : 60_000,
  expect: { timeout: 15_000 },
  // CI에서는 불안정한 테스트를 2번 다시 돌리고, 한 번에 하나씩만 돌려 안정성을 높인다
  retries: isCI ? 2 : 0,
  workers: isCI ? 1 : undefined,
  forbidOnly: isCI,
  reporter: isCI ? [['list'], ['html', { open: 'never' }]] : 'list',
  use: {
    baseURL: 'http://localhost:5176', // React(5174)·실제 확인용(5175)과 안 겹치는 전용 포트
    // Flutter의 서비스 워커(오프라인 캐시)가 테스트마다 낡은 파일을 쥐지 않게 막는다
    serviceWorkers: 'block',
    trace: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      // React 설정과 같은 이유: 이 PC에서는 정식 Edge, CI는 진짜 Chromium(새 headless). PW_CHANNEL로 바꿀 수 있다.
      use: { ...devices['Desktop Chrome'], channel: process.env.PW_CHANNEL || (isCI ? 'chromium' : 'msedge') },
    },
  ],
  webServer: {
    command: 'node serve.mjs',
    url: 'http://localhost:5176',
    reuseExistingServer: !isCI,
    timeout: 30_000,
  },
})
