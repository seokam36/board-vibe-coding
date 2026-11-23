import { expect, FIRST_LOAD, test, verifiedUser, waitForApp } from './mockApi'

// S29-a 위 막대·글자 링크(AppLink) 접근성: 스크린리더에 "링크"(진짜 <a href>) 하나로 잡히는지,
// 눌러도 문서가 다시 열리지 않는지(앱 재부팅 없음)를 본다. 모양·눌림 높이는 위젯 테스트(test/app_link_test.dart)가 맡는다.

test('비로그인 위 막대: 사이트 이름·로그인·회원가입이 링크 하나씩이고 주소가 맞다', async ({ page }) => {
  await page.goto('/')
  await waitForApp(page)

  const login = page.getByRole('link', { name: '로그인', exact: true })
  const signup = page.getByRole('link', { name: '회원가입', exact: true })
  const home = page.getByRole('link', { name: '게시판', exact: true }) // 사이트 이름
  await expect(login).toHaveCount(1)
  await expect(signup).toHaveCount(1)
  await expect(home).toHaveCount(1)
  await expect(login).toHaveAttribute('href', '/login')
  await expect(signup).toHaveAttribute('href', '/signup')
  await expect(home).toHaveAttribute('href', '/')
})

test('로그인 화면: 위 막대 "로그인"은 링크, 폼의 "로그인"은 버튼 하나로 그대로다', async ({ page }) => {
  await page.goto('/login')
  await expect(page.getByRole('textbox', { name: '아이디' })).toBeVisible({ timeout: FIRST_LOAD })
  await expect(page.getByRole('button', { name: '로그인', exact: true })).toHaveCount(1)
  await expect(page.getByRole('link', { name: '로그인', exact: true })).toHaveCount(1)
})

test('"로그인" 링크를 누르면 재로드 없이 /login으로 간다', async ({ page, api }) => {
  await page.goto('/')
  await waitForApp(page)

  let loads = 0
  page.on('load', () => loads++)
  const csrfBefore = api.sent('GET', '/csrf').length // 앱이 켜질 때 부르는 요청: 재부팅되면 늘어난다
  await page.getByRole('link', { name: '로그인', exact: true }).click()
  await expect(page).toHaveURL('/login')
  await expect(page.getByRole('textbox', { name: '아이디' })).toBeVisible({ timeout: FIRST_LOAD })
  expect(loads).toBe(0)
  expect(api.sent('GET', '/csrf')).toHaveLength(csrfBefore)
})

test('"로그인" 링크에 키보드로 포커스를 주고 Enter를 눌러도 재로드 없이 /login으로 간다', async ({ page, api }) => {
  await page.goto('/')
  await waitForApp(page)

  let loads = 0
  page.on('load', () => loads++)
  const csrfBefore = api.sent('GET', '/csrf').length
  const login = page.getByRole('link', { name: '로그인', exact: true })
  await login.focus() // Tab으로 가는 것과 같은 포커스 (Tab 순서는 손 확인표)
  await expect(login).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL('/login')
  await expect(page.getByRole('textbox', { name: '아이디' })).toBeVisible({ timeout: FIRST_LOAD })
  expect(loads).toBe(0)
  expect(api.sent('GET', '/csrf')).toHaveLength(csrfBefore)
})

test('로그인 상태: "챗봇"이 링크 하나이고 주소는 /chat', async ({ page, api }) => {
  api.setUser(verifiedUser)
  await page.goto('/')
  await waitForApp(page)
  const chat = page.getByRole('link', { name: '챗봇', exact: true })
  await expect(chat).toHaveCount(1)
  await expect(chat).toHaveAttribute('href', '/chat')
})

test('없는 주소(404): "홈으로"가 링크 하나이고 주소는 /', async ({ page }) => {
  await page.goto('/no-such')
  await waitForApp(page)
  const home = page.getByRole('link', { name: '홈으로', exact: true })
  await expect(home).toHaveCount(1)
  await expect(home).toHaveAttribute('href', '/')
})

test('비로그인 중고 목록: "로그인" 링크가 위 막대와 글쓰기 안내에 하나씩 있고 안내 쪽도 /login으로 재로드 없이 간다', async ({ page, api }) => {
  await page.goto('/market')
  await waitForApp(page)

  const logins = page.getByRole('link', { name: '로그인', exact: true })
  await expect(logins).toHaveCount(2) // 위 막대 + 글쓰기 안내
  // 위 막대 링크는 한 겹: 안쪽에 접근성 노드가 또 없다 (feed-a11y.spec.ts와 같은 방식)
  expect(await logins.first().locator('flt-semantics').count()).toBe(0)
  // 안내 쪽 링크 (DOM 순서상 위 막대 다음)
  const notice = logins.last()
  await expect(notice).toHaveAttribute('href', '/login')

  let loads = 0
  page.on('load', () => loads++)
  const csrfBefore = api.sent('GET', '/csrf').length
  await notice.click()
  await expect(page).toHaveURL('/login')
  await expect(page.getByRole('textbox', { name: '아이디' })).toBeVisible({ timeout: FIRST_LOAD })
  expect(loads).toBe(0)
  expect(api.sent('GET', '/csrf')).toHaveLength(csrfBefore)
})
