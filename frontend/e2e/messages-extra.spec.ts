import { test, expect, fakeConversationDetail, fakeConversationSummary, fakeUser, loginVerified } from './mockApi.ts'
import type { Page } from '@playwright/test'
import type { ConversationDetail, MarketItem } from '../src/types.ts'

// S14-d 보충: messages.spec.ts에 빠진 완료 조건 (예약중·관리자·길이 한도·중복 클릭·시각·401·주소·상단바)

const RELOGIN = '다시 로그인해 주세요 로그인' // 안내 문구 + 로그인 링크 글자

function makeItem(over: Partial<{ sellerId: number; status: MarketItem['status']['code'] }> = {}): MarketItem {
  const status = over.status ?? 'ON_SALE'
  const names = { ON_SALE: '판매중', RESERVED: '예약중', DONE: '거래완료' } as const
  return {
    id: 1,
    title: '가짜 자전거',
    content: '거의 새 자전거입니다.',
    tradeType: { code: 'SALE', name: '판매' },
    price: 5000,
    status: { code: status, name: names[status] },
    school: { id: 1, name: '가짜대학교' },
    seller: { id: over.sellerId ?? 5, nickname: over.sellerId === 1 ? '테스터' : '판매자닉' },
    createdAt: '2026-01-02T00:00:00Z',
    photos: [],
  }
}

// 지정한 메서드·경로로 나간 요청을 모은다
function record(page: Page, method: string, pathname: string) {
  const calls: { body: string | null }[] = []
  page.on('request', (r) => {
    if (r.method() === method && new URL(r.url()).pathname === pathname) calls.push({ body: r.postData() })
  })
  return calls
}

const composer = (page: Page) => page.getByLabel('쪽지 내용')
const sendButton = (page: Page) => page.getByRole('button', { name: '쪽지 보내기' })

// ---- 1. 상세: 보낼 수 있는 경우 ----

test('예약중인 글에는 쪽지 입력칸과 보내기 버튼이 있다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem({ status: 'RESERVED' }) } })
  await page.goto('/market/1')
  await expect(composer(page)).toBeVisible()
  await expect(sendButton(page)).toBeVisible()
  await expect(page.getByText('새 대화를 시작할 수 없어요')).toHaveCount(0)
})

test('관리자도 남의 글이면 입력칸이 있다 (일반 회원과 같은 규칙)', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN', verifiedSchool: { id: 1, name: '가짜대학교' } } },
    'GET /market-items/1': { status: 200, body: makeItem() },
  })
  await page.goto('/market/1')
  await expect(composer(page)).toBeVisible()
  await expect(sendButton(page)).toBeVisible()
})

test('관리자도 내 글이면 입력칸도 안내도 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN', verifiedSchool: { id: 1, name: '가짜대학교' } } },
    'GET /market-items/1': { status: 200, body: makeItem({ sellerId: 1 }) },
  })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(composer(page)).toHaveCount(0)
  await expect(page.getByText('쪽지를 보낼 수 있')).toHaveCount(0)
  await expect(page.getByText('새 대화를 시작할 수 없어요')).toHaveCount(0)
})

test('관리자가 미인증이면 일반 회원과 같이 학교 인증 안내가 보인다', async ({ page, api }) => {
  api.setLoggedIn(true)
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN', verifiedSchool: null } },
    'GET /market-items/1': { status: 200, body: makeItem() },
  })
  await page.goto('/market/1')
  await expect(page.getByText('학교 인증 후 같은 학교 글의 판매자에게 쪽지를 보낼 수 있어요')).toBeVisible()
  await expect(composer(page)).toHaveCount(0)
})

// ---- 2. 보내기 요청과 대화 화면 ----

test('보내기 요청은 /api/market-items/1/conversations로 가고 본문은 {content}뿐이며 대화 화면에 쪽지가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  const urls: string[] = []
  page.on('request', (r) => {
    if (r.method() === 'POST' && new URL(r.url()).pathname.includes('/conversations')) urls.push(new URL(r.url()).pathname)
  })
  const calls = record(page, 'POST', '/api/market-items/1/conversations')
  await page.goto('/market/1')
  await composer(page).fill('안녕하세요, 아직 있나요?')
  await sendButton(page).click()
  await expect(page).toHaveURL(/\/messages\/3$/)
  expect(urls).toEqual(['/api/market-items/1/conversations'])
  expect(calls).toHaveLength(1)
  expect(Object.keys(JSON.parse(calls[0].body ?? '{}'))).toEqual(['content'])
  // 대화 화면에 방금 보낸 쪽지가 있다
  await expect(page.locator('.msg-mine').first()).toContainText('안녕하세요, 아직 있나요?')
})

// ---- 3. 길이·중복 클릭 ----

test('1000자를 넘게 쓰려 하면 1000자까지만 들어가고 글자 수는 1000 / 1000이다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  await page.goto('/market/1')
  await composer(page).fill('가'.repeat(1100))
  const value = await composer(page).inputValue()
  expect(value.length).toBe(1000)
  await expect(page.getByText('1000 / 1000')).toBeVisible()
  await expect(sendButton(page)).toBeEnabled()
})

test('보내기 버튼을 연달아 두 번 눌러도 요청은 한 번만 나간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => (release = resolve))
  await page.route('**/api/market-items/1/conversations', async (route) => {
    await gate
    await route.fulfill({
      status: 201,
      json: { conversationId: 3, message: { id: 10, content: '안녕', createdAt: '2026-01-02T03:00:00Z', mine: true } },
    })
  })
  const calls = record(page, 'POST', '/api/market-items/1/conversations')
  await page.goto('/market/1')
  await composer(page).fill('안녕')
  await sendButton(page).dblclick()
  await expect(page.getByRole('button', { name: '보내는 중...' })).toBeDisabled()
  // 두 번째 클릭이 처리될 시간을 조금 준 뒤에도 한 번이어야 한다
  await page.waitForTimeout(300)
  expect(calls).toHaveLength(1)
  release()
  await expect(page).toHaveURL(/\/messages\/3$/)
  expect(calls).toHaveLength(1)
})

test('답장 보내기도 연달아 두 번 눌러도 요청은 한 번만 나간다', async ({ page, api }) => {
  loginVerified(api)
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => (release = resolve))
  await page.route('**/api/conversations/3/messages', async (route) => {
    await gate
    await route.fulfill({ status: 201, json: { id: 12, content: '답장입니다', createdAt: '2026-01-02T03:10:00Z', mine: true } })
  })
  const calls = record(page, 'POST', '/api/conversations/3/messages')
  await page.goto('/messages/3')
  await page.getByLabel('답장 내용').fill('답장입니다')
  await page.getByRole('button', { name: '답장 보내기' }).dblclick()
  await expect(page.getByRole('button', { name: '보내는 중...' })).toBeDisabled()
  await page.waitForTimeout(300)
  expect(calls).toHaveLength(1)
  release()
  await expect(page.locator('.msg')).toHaveCount(3)
  expect(calls).toHaveLength(1)
})

// ---- 4. 쪽지함 시각·링크 ----

test('쪽지함 줄에 마지막 쪽지 시각(서울 시간)이 보이고 링크 주소는 /messages/3이다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations': { status: 200, body: [fakeConversationSummary] } })
  await page.goto('/messages')
  const row = page.locator('.row').first()
  // 2026-01-02T03:05Z = 서울 12:05
  await expect(row).toContainText(/1월 2일 12:05/)
  await expect(row.getByRole('link')).toHaveAttribute('href', '/messages/3')
  // 상대가 보낸 쪽지에는 "나: "가 없다
  await expect(row).toContainText('아직 있어요')
  await expect(row).not.toContainText('나:')
})

// ---- 5. 대화 화면 주소·시각·잘못된 id ----

test('대화 화면의 쪽지에 서울 시각이 보이고 불러오기 주소는 /api/conversations/3이다', async ({ page, api }) => {
  loginVerified(api)
  const paths: string[] = []
  page.on('request', (r) => {
    const p = new URL(r.url()).pathname
    // 상단바가 부르는 안 읽은 수(unread-count)는 대화 불러오기가 아니므로 뺀다
    if (r.method() === 'GET' && p.startsWith('/api/conversations') && p !== '/api/conversations/unread-count') paths.push(p)
  })
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect(page.locator('.msg').nth(0)).toContainText(/1월 2일 12:00/)
  await expect(page.locator('.msg').nth(1)).toContainText(/1월 2일 12:05/)
  // 개발 서버(StrictMode)는 효과를 두 번 돌려 같은 요청이 2번 갈 수 있다. 다른 주소로 가지 않는 것만 본다
  expect(paths.length).toBeGreaterThan(0)
  expect(paths.every((p) => p === '/api/conversations/3')).toBe(true)
})

test('숫자가 아닌 id(/messages/abc)는 서버가 400을 주면 "대화를 찾을 수 없습니다"가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/abc': { status: 400 } })
  await page.goto('/messages/abc')
  await expect(page.getByRole('alert')).toHaveText('대화를 찾을 수 없습니다')
  await expect(page.getByRole('link', { name: '쪽지함으로' })).toBeVisible()
  await expect(page.getByLabel('답장 내용')).toHaveCount(0)
})

test('숫자가 아닌 id(/messages/abc)가 404여도 같은 문구가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/abc': { status: 404 } })
  await page.goto('/messages/abc')
  await expect(page.getByRole('alert')).toHaveText('대화를 찾을 수 없습니다')
})

test('비로그인으로 /messages/3을 열면 /login으로 간다', async ({ page }) => {
  await page.goto('/messages/3')
  await expect(page).toHaveURL(/\/login$/)
})

test('/messages/1%3Fx=1 처럼 ?가 섞인 id는 다른 API로 바뀌지 않고 /api/conversations/1%3Fx%3D1로 간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/1%3Fx%3D1': { status: 404 } })
  await page.goto('/messages/1%3Fx=1')
  await expect(page.getByRole('alert')).toHaveText('대화를 찾을 수 없습니다')
})

// ---- 7. 401 (보내기·답장 중 로그아웃) ----

test('쪽지 보내기가 401이고 재발급도 401이면 다시 로그인 안내가 보이고 쓴 내용과 주소는 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'POST /market-items/1/conversations': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/market/1')
  await composer(page).fill('안녕하세요')
  await sendButton(page).click()
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  await expect(composer(page)).toHaveValue('안녕하세요')
  await expect(page).toHaveURL(/\/market\/1$/)
})

test('답장이 401이고 재발급도 401이면 다시 로그인 안내가 보이고 쓴 내용과 쪽지 목록은 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'POST /conversations/3/messages': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/messages/3')
  await page.getByLabel('답장 내용').fill('답장입니다')
  await page.getByRole('button', { name: '답장 보내기' }).click()
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  await expect(page.getByLabel('답장 내용')).toHaveValue('답장입니다')
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect(page).toHaveURL(/\/messages\/3$/)
})

test('대화 불러오기가 401이고 재발급도 401이면 다시 로그인 안내만 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /conversations/3': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/messages/3')
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  await expect(page.getByLabel('답장 내용')).toHaveCount(0)
})

test('쪽지 보내기가 401이어도 재발급이 성공하면 같은 요청이 다시 가서 대화 화면으로 간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  // 첫 요청만 401, 재발급(기본 200) 뒤 두 번째 요청은 201
  let n = 0
  await page.route('**/api/market-items/1/conversations', async (route) => {
    n++
    if (n === 1) await route.fulfill({ status: 401 })
    else
      await route.fulfill({
        status: 201,
        json: { conversationId: 3, message: { id: 10, content: '안녕', createdAt: '2026-01-02T03:00:00Z', mine: true } },
      })
  })
  await page.goto('/market/1')
  await composer(page).fill('안녕')
  await sendButton(page).click()
  await expect(page).toHaveURL(/\/messages\/3$/)
  expect(n).toBe(2)
})

// ---- 8. 상단바 ----

test('상단바 "쪽지" 링크는 /messages로 가고, 안 읽은 수가 0이면 숫자 없이 "쪽지"만 보이며 로그인했으니 안 읽은 수를 부른다', async ({ page, api }) => {
  loginVerified(api)
  const unreadCalls: string[] = []
  page.on('request', (r) => {
    if (new URL(r.url()).pathname === '/api/conversations/unread-count') unreadCalls.push(r.url())
  })
  await page.goto('/')
  const link = page.getByRole('link', { name: '쪽지', exact: true })
  await expect(link).toHaveText('쪽지')
  await expect(link).toHaveAttribute('href', '/messages')
  await link.click()
  await expect(page).toHaveURL(/\/messages$/)
  await expect(page.getByText('아직 쪽지가 없습니다')).toBeVisible()
  await expect(page.getByRole('link', { name: '쪽지', exact: true })).toHaveText('쪽지')
  // 개발 서버(StrictMode)는 효과를 두 번 돌려 2번 갈 수 있다. 부르긴 했는지만 본다
  expect(unreadCalls.length).toBeGreaterThan(0)
})

// ---- 9. 대화 사이를 브라우저 기록으로 건너뛸 때 (같은 화면 컴포넌트가 재사용되는 경우) ----

test('대화 5에서 쓰던 답장은 대화 3으로 건너뛰면 사라지고, 답장은 화면에 보이는 대화로 간다', async ({ page, api }) => {
  loginVerified(api)
  const detail5: ConversationDetail = { ...fakeConversationDetail, id: 5, partner: { id: 8, nickname: '다른닉' } }
  api.override({ 'GET /conversations/5': { status: 200, body: detail5 }, 'PUT /conversations/5/read': { status: 204 } })
  const calls3 = record(page, 'POST', '/api/conversations/3/messages')
  const calls5 = record(page, 'POST', '/api/conversations/5/messages')

  // 대화 3을 연 뒤 같은 탭 안에서 대화 5로 이동 (새로고침 없이 주소만 바뀌어 컴포넌트가 그대로 재사용된다)
  await page.goto('/messages/3')
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
  // (e2e 폴더는 브라우저 타입이 없어 코드를 문자열로 넘긴다. hasHorizontalScroll과 같은 방식)
  await page.evaluate("window.history.pushState({}, '', '/messages/5'); window.dispatchEvent(new PopStateEvent('popstate'))")
  await expect(page.getByRole('heading', { name: '다른닉' })).toBeVisible()
  await page.getByLabel('답장 내용').fill('5번에게 쓰던 글')

  // 대화 3의 응답을 붙잡아 두고 "뒤로 가기"로 3번으로 돌아간다
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => (release = resolve))
  await page.route('**/api/conversations/3', async (route) => {
    await gate
    await route.fulfill({ status: 200, json: fakeConversationDetail })
  })
  await page.goBack()
  await expect(page).toHaveURL(/\/messages\/3$/)
  // 새 대화가 오기 전에 옛 대화(다른닉)가 남아 있으면 안 된다
  await expect(page.getByRole('heading', { name: '다른닉' })).toHaveCount(0)
  release()

  // 3번이 보이면 입력칸은 비어 있고, 이어서 쓴 답장은 3번으로만 간다
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
  await expect(page.getByLabel('답장 내용')).toHaveValue('')
  await page.getByLabel('답장 내용').fill('3번에게 쓰는 글')
  await page.getByRole('button', { name: '답장 보내기' }).click()
  await expect(page.locator('.msg')).toHaveCount(3)
  expect(calls3).toHaveLength(1)
  expect(calls5).toHaveLength(0)
})

test('비로그인 상단바에는 "쪽지" 링크가 없고 안 읽은 수도 부르지 않는다', async ({ page, api }) => {
  const unreadCalls: string[] = []
  page.on('request', (r) => {
    if (new URL(r.url()).pathname === '/api/conversations/unread-count') unreadCalls.push(r.url())
  })
  await page.goto('/')
  await expect(page.getByRole('link', { name: '로그인' }).first()).toBeVisible()
  await expect(page.getByRole('link', { name: '쪽지' })).toHaveCount(0)
  await api.waitIdle() // 늦게 나가는 요청이 있어도 검사에 잡히게 기다린다
  expect(unreadCalls).toEqual([])
})

// ---- 10. 안 읽은 쪽지 (S14-e): 상단바 숫자와 대화 화면의 읽음 처리 ----

test('안 읽은 쪽지가 3개면 상단바가 "쪽지 (3)"으로 보이고 링크는 /messages다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/unread-count': { status: 200, body: { count: 3 } } })
  await page.goto('/')
  const link = page.getByRole('link', { name: '쪽지 (3)' })
  await expect(link).toBeVisible()
  await expect(link).toHaveAttribute('href', '/messages')
})

test('새로고침 없이 화면을 옮기면 그사이 온 쪽지 수가 상단바에 반영된다', async ({ page, api }) => {
  loginVerified(api)
  // 처음엔 0개였는데 보는 동안 새 쪽지 3통이 온 상황을 흉내 낸다
  let unread = 0
  api.override({
    'GET /conversations': { status: 200, body: [fakeConversationSummary] },
    'GET /conversations/unread-count': () => ({ status: 200, body: { count: unread } }),
  })
  // 첫 안 읽은 수 응답(0개)이 화면에 반영될 때까지 기다린 뒤에 새 쪽지를 "도착"시킨다 (안 기다리면 첫 응답이 3으로 와서 이동 없이도 통과해 버린다)
  const first = page.waitForResponse((r) => new URL(r.url()).pathname === '/api/conversations/unread-count')
  await page.goto('/')
  await first
  await expect(page.getByRole('link', { name: '쪽지', exact: true })).toBeVisible()
  await api.waitIdle()
  unread = 3
  // 화면 이동(주소 바뀜) = 숫자를 다시 센다
  await page.getByRole('link', { name: '쪽지', exact: true }).click()
  await expect(page).toHaveURL(/\/messages$/)
  await expect(page.getByRole('link', { name: '쪽지 (3)' })).toBeVisible()
})

test('대화를 열면 마지막 쪽지 번호로 읽음 처리 PUT이 가고 상단바 숫자가 줄어든다', async ({ page, api }) => {
  loginVerified(api)
  // 읽음 처리가 끝나면 서버의 안 읽은 수가 0이 되는 흐름을 흉내 낸다
  let unread = 2
  api.override({
    'GET /conversations': { status: 200, body: [fakeConversationSummary] },
    'GET /conversations/unread-count': () => ({ status: 200, body: { count: unread } }),
    'PUT /conversations/3/read': () => {
      unread = 0
      return { status: 204 }
    },
  })
  const calls = record(page, 'PUT', '/api/conversations/3/read')
  await page.goto('/')
  await expect(page.getByRole('link', { name: '쪽지 (2)' })).toBeVisible()
  await page.getByRole('link', { name: '쪽지 (2)' }).click()
  await page.locator('.row').first().getByRole('link').click()
  await expect(page).toHaveURL(/\/messages\/3$/)
  await expect(page.locator('.msg')).toHaveCount(2)
  // 숫자가 사라지고 "쪽지"만 남는다
  await expect(page.getByRole('link', { name: '쪽지', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: '쪽지 (2)' })).toHaveCount(0)
  // 마지막 쪽지(번호 11)까지 봤다고 알렸다. StrictMode로 두 번 갈 수 있어 본문은 모두 같아야 한다
  expect(calls.length).toBeGreaterThan(0)
  for (const c of calls) expect(JSON.parse(c.body ?? '{}')).toEqual({ lastMessageId: 11 })
})

test('읽음 처리 PUT이 실패해도 대화 화면은 그대로 보이고 에러 안내는 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /conversations/unread-count': { status: 200, body: { count: 2 } },
    'PUT /conversations/3/read': { status: 500 },
  })
  const calls = record(page, 'PUT', '/api/conversations/3/read')
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect(page.getByLabel('답장 내용')).toBeVisible()
  await api.waitIdle()
  expect(calls.length).toBeGreaterThan(0) // PUT은 실제로 갔다
  await expect(page.getByRole('alert')).toHaveCount(0)
  // 읽음 처리가 실패했으니 숫자는 그대로다
  await expect(page.getByRole('link', { name: '쪽지 (2)' })).toBeVisible()
})

test('쪽지가 하나도 없는 대화를 열면 읽음 처리 PUT을 보내지 않는다', async ({ page, api }) => {
  loginVerified(api)
  const detail: ConversationDetail = { ...fakeConversationDetail, messages: [] }
  api.override({ 'GET /conversations/3': { status: 200, body: detail } })
  const calls = record(page, 'PUT', '/api/conversations/3/read')
  await page.goto('/messages/3')
  await expect(page.getByText('아직 쪽지가 없습니다')).toBeVisible()
  await api.waitIdle()
  expect(calls).toHaveLength(0)
})

// 안 읽은 수 요청이 500이어도 화면은 멀쩡하고 "쪽지"만 보인다 (부가 표시라 조용히 무시)
test('안 읽은 수 요청이 500이어도 상단바는 "쪽지"로 보이고 에러 안내는 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/unread-count': { status: 500 } })
  await page.goto('/')
  await expect(page.getByRole('link', { name: '쪽지', exact: true })).toBeVisible()
  await api.waitIdle()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByRole('link', { name: '쪽지', exact: true })).toHaveText('쪽지')
})

// 대화 3 -> 5로 이동하면 5의 마지막 쪽지 번호(21)로 PUT이 가야 한다 (3의 번호 11이 섞이면 안 됨)
test('대화 3에서 대화 5로 이동하면 대화 5의 마지막 쪽지 번호로 읽음 처리 PUT이 간다', async ({ page, api }) => {
  loginVerified(api)
  const detail5: ConversationDetail = {
    ...fakeConversationDetail,
    id: 5,
    partner: { id: 8, nickname: '다른닉' },
    messages: [
      { id: 20, content: '다른 대화 첫 쪽지', createdAt: '2026-01-03T03:00:00Z', mine: true },
      { id: 21, content: '다른 대화 마지막 쪽지', createdAt: '2026-01-03T03:05:00Z', mine: false },
    ],
  }
  api.override({ 'GET /conversations/5': { status: 200, body: detail5 }, 'PUT /conversations/5/read': { status: 204 } })
  const calls3 = record(page, 'PUT', '/api/conversations/3/read')
  const calls5 = record(page, 'PUT', '/api/conversations/5/read')
  await page.goto('/messages/3')
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
  await page.evaluate("window.history.pushState({}, '', '/messages/5'); window.dispatchEvent(new PopStateEvent('popstate'))")
  await expect(page.getByRole('heading', { name: '다른닉' })).toBeVisible()
  await api.waitIdle()
  expect(calls3.length).toBeGreaterThan(0)
  for (const c of calls3) expect(JSON.parse(c.body ?? '{}')).toEqual({ lastMessageId: 11 })
  expect(calls5.length).toBeGreaterThan(0)
  for (const c of calls5) expect(JSON.parse(c.body ?? '{}')).toEqual({ lastMessageId: 21 })
})

// 로그인 화면에서 로그인하자마자 숫자가 떠야 한다 (새로고침 없이)
test('로그인 직후 상단바에 안 읽은 수가 바로 보인다', async ({ page, api }) => {
  api.override({ 'GET /conversations/unread-count': { status: 200, body: { count: 4 } } })
  await page.goto('/login')
  await expect(page.getByRole('link', { name: /쪽지/ })).toHaveCount(0)
  await page.getByLabel('아이디').fill(fakeUser.username)
  await page.getByLabel('비밀번호').fill('아무비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(page.getByRole('link', { name: '쪽지 (4)' })).toBeVisible()
})

// 답장은 읽음 처리와 별개: 답장을 보내도 PUT은 다시 안 가고 숫자는 그대로, 에러도 없다
test('답장을 보내도 읽음 처리 PUT은 다시 가지 않고 상단바 숫자는 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/unread-count': { status: 200, body: { count: 2 } } })
  const calls = record(page, 'PUT', '/api/conversations/3/read')
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  // 처음 열 때의 읽음 처리 PUT이 나갈 때까지 기다린다 (쪽지가 보인 직후엔 아직 안 나갔을 수 있다)
  await expect.poll(() => calls.length).toBeGreaterThan(0)
  await api.waitIdle()
  const before = calls.length
  await page.getByLabel('답장 내용').fill('답장입니다')
  await page.getByRole('button', { name: '답장 보내기' }).click()
  await expect(page.locator('.msg')).toHaveCount(3)
  await api.waitIdle()
  expect(calls.length).toBe(before)
  await expect(page.getByRole('link', { name: '쪽지 (2)' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('로그아웃하면 상단바 숫자가 사라진다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /conversations/unread-count': { status: 200, body: { count: 3 } },
    'POST /auth/logout': { status: 204 },
  })
  await page.goto('/')
  await expect(page.getByRole('link', { name: '쪽지 (3)' })).toBeVisible()
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('link', { name: '로그인' }).first()).toBeVisible()
  await expect(page.getByRole('link', { name: /쪽지/ })).toHaveCount(0)
})
