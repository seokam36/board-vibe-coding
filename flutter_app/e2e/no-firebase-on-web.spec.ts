import { expect, test, waitForApp } from './mockApi'

// S24-d: 푸시 알림(Firebase)은 앱(안드로이드) 전용이다. 웹 화면에는 Firebase가 섞이면 안 된다.
// firebase 패키지는 웹 빌드에 플러그인으로 들어가면 구글 서버(gstatic.com/firebasejs)에서 JS를 내려받으려 할 수 있어서,
// 웹 첫 화면을 여는 동안 그런 요청이 한 번도 나가지 않는지, 기기 토큰 API도 안 부르는지 확인한다.
test('웹 첫 화면을 열어도 Firebase(gstatic.com/firebasejs)로 나가는 요청이 없고 기기 토큰 API도 안 부른다', async ({ page, api }) => {
  const firebaseRequests: string[] = []
  page.on('request', (r) => {
    if (r.url().includes('gstatic.com/firebasejs') || r.url().includes('firebase')) firebaseRequests.push(r.url())
  })

  await page.goto('/')
  await waitForApp(page) // 첫 로딩이 끝날 때까지 기다린 뒤에 센다

  expect(firebaseRequests).toEqual([])
  expect(api.requests.filter((r) => r.path.includes('device-tokens'))).toEqual([])
})
