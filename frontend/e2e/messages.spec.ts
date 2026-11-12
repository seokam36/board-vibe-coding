import { test, expect, loginVerified, fakeConversationDetail, fakeConversationSummary } from './mockApi.ts'
import type { Page } from '@playwright/test'
import type { ConversationDetail, MarketItem } from '../src/types.ts'

// S14-d: 쪽지 화면 (상세의 "판매자에게 쪽지", /messages 쪽지함, /messages/:id 대화). 서버 응답은 mockApi 가짜 응답이다

const school = { id: 1, name: '가짜대학교' }

// 가짜 중고 글. 판매자 id가 1이면 "내 글"(로그인 가짜 회원 id = 1)
function makeItem(over: Partial<{ sellerId: number; status: MarketItem['status']['code']; schoolId: number }> = {}): MarketItem {
  const status = over.status ?? 'ON_SALE'
  const names = { ON_SALE: '판매중', RESERVED: '예약중', DONE: '거래완료' } as const
  return {
    id: 1,
    title: '가짜 자전거',
    content: '거의 새 자전거입니다.',
    tradeType: { code: 'SALE', name: '판매' },
    price: 5000,
    status: { code: status, name: names[status] },
    school: over.schoolId ? { id: over.schoolId, name: '다른대학교' } : school,
    seller: { id: over.sellerId ?? 5, nickname: over.sellerId === 1 ? '테스터' : '판매자닉' },
    createdAt: '2026-01-02T00:00:00Z',
    photos: [],
  }
}

// 지정한 메서드·경로로 나간 요청을 모은다 (본문 확인용)
function record(page: Page, method: string, pathname: string) {
  const calls: { body: string | null }[] = []
  page.on('request', (r) => {
    if (r.method() === method && new URL(r.url()).pathname === pathname) calls.push({ body: r.postData() })
  })
  return calls
}

async function hasHorizontalScroll(page: Page) {
  return page.evaluate<boolean>('document.documentElement.scrollWidth > document.documentElement.clientWidth')
}

// 쪽지 칸의 글쓰기 영역과 보내기 버튼
const composer = (page: Page) => page.getByLabel('쪽지 내용')
const sendButton = (page: Page) => page.getByRole('button', { name: '쪽지 보내기' })

// ---- 상세: 판매자에게 쪽지 ----

test('인증 회원이 남의 글에서 쪽지를 보내면 POST가 나가고 /messages/3 대화 화면으로 간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  const calls = record(page, 'POST', '/api/market-items/1/conversations')
  await page.goto('/market/1')
  await composer(page).fill('안녕하세요, 아직 있나요?')
  await expect(page.getByText('14 / 1000')).toBeVisible()
  await sendButton(page).click()

  await expect(page).toHaveURL(/\/messages\/3$/)
  expect(calls).toHaveLength(1)
  expect(JSON.parse(calls[0].body ?? '{}')).toEqual({ content: '안녕하세요, 아직 있나요?' })
  // 대화 화면이 열렸다 (상대 닉네임 제목)
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
})

test('쪽지가 비었거나 공백뿐이면 보내기 버튼이 비활성이고 글자 수는 0 / 1000이다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  await page.goto('/market/1')
  await expect(page.getByText('0 / 1000')).toBeVisible()
  await expect(sendButton(page)).toBeDisabled()
  await composer(page).fill('   \n  ')
  await expect(sendButton(page)).toBeDisabled()
  await composer(page).fill('안녕')
  await expect(sendButton(page)).toBeEnabled()
})

test('보내는 중에는 버튼이 잠기고 "보내는 중..."이 보인다 (두 번 눌림 방지)', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  // 응답을 늦춰서 "보내는 중" 상태를 잡는다
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
  await sendButton(page).click()
  await expect(page.getByRole('button', { name: '보내는 중...' })).toBeDisabled()
  expect(calls).toHaveLength(1)
  release()
  await expect(page).toHaveURL(/\/messages\/3$/)
})

test('내 글에는 쪽지 칸도 안내도 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem({ sellerId: 1 }) } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByText('판매자에게 쪽지')).toHaveCount(0)
  await expect(composer(page)).toHaveCount(0)
  await expect(page.getByText('쪽지를 보낼 수 있')).toHaveCount(0)
})

test('비로그인이면 입력칸 대신 로그인 안내가 보인다', async ({ page, api }) => {
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  await page.goto('/market/1')
  await expect(page.getByText('로그인하면 판매자에게 쪽지를 보낼 수 있습니다')).toBeVisible()
  await expect(composer(page)).toHaveCount(0)
})

test('학교 인증 전이면 입력칸 대신 학교 인증 안내가 보인다', async ({ page, api }) => {
  api.setLoggedIn(true) // 로그인만 하고 인증은 안 한 회원
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  await page.goto('/market/1')
  await expect(page.getByText('학교 인증 후 같은 학교 글의 판매자에게 쪽지를 보낼 수 있어요')).toBeVisible()
  await expect(composer(page)).toHaveCount(0)
})

test('다른 학교 글이면 입력칸 대신 인증한 학교 안내가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem({ schoolId: 2 }) } })
  await page.goto('/market/1')
  await expect(page.getByText('인증한 학교(가짜대학교) 글에만 쪽지를 보낼 수 있어요')).toBeVisible()
  await expect(composer(page)).toHaveCount(0)
})

test('거래완료 글이면 입력칸 대신 안내와 쪽지함 링크가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem({ status: 'DONE' }) } })
  await page.goto('/market/1')
  await expect(page.locator('.seller-message-hint')).toHaveText(
    '거래가 완료된 글이라 새 대화를 시작할 수 없어요. 이미 나눈 대화는 쪽지함에서 이어 갈 수 있어요 쪽지함 가기',
  )
  await expect(page.getByRole('link', { name: '쪽지함 가기' })).toHaveAttribute('href', '/messages')
  await expect(composer(page)).toHaveCount(0)
})

for (const [status, text] of [
  [400, '쪽지 내용을 입력해 주세요 (최대 1000자)'],
  [403, '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요'],
  [404, '이미 삭제된 글입니다'],
  [409, '거래가 완료된 글이라 새 대화를 시작할 수 없어요. 이미 나눈 대화는 쪽지함에서 이어 갈 수 있어요'],
  [500, '쪽지를 보내지 못했습니다'],
] as const) {
  test(`쪽지 보내기가 ${status}이면 "${text}"가 보이고 쓴 내용은 남는다`, async ({ page, api }) => {
    loginVerified(api)
    api.override({
      'GET /market-items/1': { status: 200, body: makeItem() },
      'POST /market-items/1/conversations': { status },
    })
    await page.goto('/market/1')
    await composer(page).fill('안녕하세요')
    await sendButton(page).click()
    await expect(page.getByRole('alert')).toHaveText(text)
    await expect(composer(page)).toHaveValue('안녕하세요')
    await expect(sendButton(page)).toBeEnabled()
  })
}

// ---- 쪽지함 ----

test('상단바 "쪽지" 링크는 로그인했을 때만 보이고, 누르면 쪽지함이 열린다', async ({ page, api }) => {
  await page.goto('/')
  await expect(page.getByRole('link', { name: '쪽지' })).toHaveCount(0)

  loginVerified(api)
  await page.goto('/')
  await page.getByRole('link', { name: '쪽지', exact: true }).click()
  await expect(page).toHaveURL(/\/messages$/)
  await expect(page.getByText('아직 쪽지가 없습니다')).toBeVisible()
})

test('비로그인으로 /messages를 열면 /login으로 간다', async ({ page }) => {
  await page.goto('/messages')
  await expect(page).toHaveURL(/\/login$/)
})

test('쪽지함 목록: 상대 닉네임·글 제목·마지막 쪽지가 보이고 줄을 누르면 대화로 간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /conversations': {
      status: 200,
      body: [
        { ...fakeConversationSummary, id: 3, lastMessage: { content: '내일 3시에 만나요', createdAt: '2026-01-02T03:05:00Z', mine: true } },
        {
          ...fakeConversationSummary,
          id: 2,
          item: { id: null, title: '지워진 책상', deleted: true },
          partner: null,
          lastMessage: { content: '감사합니다', createdAt: '2026-01-01T03:05:00Z', mine: false },
        },
      ],
    },
  })
  await page.goto('/messages')
  const rows = page.locator('.row')
  await expect(rows).toHaveCount(2)
  // 첫 줄: 상대 닉네임 · 글 제목 · 내가 보낸 마지막 쪽지("나: ")
  await expect(rows.nth(0)).toContainText('판매자닉')
  await expect(rows.nth(0)).toContainText('가짜 자전거')
  await expect(rows.nth(0)).toContainText('나: 내일 3시에 만나요')
  // 둘째 줄: 탈퇴한 상대 + 지워진 글 표시
  await expect(rows.nth(1)).toContainText('탈퇴한 회원')
  await expect(rows.nth(1)).toContainText('삭제된 글')
  await expect(rows.nth(1)).toContainText('지워진 책상')
  await expect(rows.nth(1)).not.toContainText('나:')

  await rows.nth(0).getByRole('link').click()
  await expect(page).toHaveURL(/\/messages\/3$/)
  await expect(page.getByText('안녕하세요, 아직 있나요?')).toBeVisible()
})

test('쪽지함 불러오기가 401이고 재발급도 401이면 다시 로그인 안내와 로그인 링크가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /conversations': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/messages')
  await expect(page.getByRole('alert')).toHaveText('다시 로그인해 주세요 로그인')
  await expect(page.getByRole('alert').getByRole('link', { name: '로그인' })).toBeVisible()
})

test('쪽지함이 비어 있으면 "아직 쪽지가 없습니다"가 보인다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/messages')
  await expect(page.getByText('아직 쪽지가 없습니다')).toBeVisible()
  await expect(page.locator('.row')).toHaveCount(0)
})

test('쪽지함을 못 불러오면 실패 문구가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations': { status: 500 } })
  await page.goto('/messages')
  await expect(page.getByRole('alert')).toHaveText('쪽지함을 불러오지 못했습니다')
})

test('긴 마지막 쪽지가 있어도 375px에서 가로 스크롤이 없다', async ({ page, api }) => {
  await page.setViewportSize({ width: 375, height: 800 })
  loginVerified(api)
  api.override({
    'GET /conversations': {
      status: 200,
      body: [{ ...fakeConversationSummary, lastMessage: { content: '아주 긴 쪽지 '.repeat(60), createdAt: '2026-01-02T03:05:00Z', mine: false } }],
    },
  })
  await page.goto('/messages')
  await expect(page.locator('.row')).toHaveCount(1)
  expect(await hasHorizontalScroll(page)).toBe(false)
})

// ---- 대화 화면 ----

test('대화 화면: 상대 닉네임과 글 링크가 위에, 내 쪽지는 오른쪽·상대 쪽지는 왼쪽 말풍선으로 보인다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/messages/3')
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
  await expect(page.getByRole('link', { name: '가짜 자전거' })).toHaveAttribute('href', '/market/1')
  const items = page.locator('.msg')
  await expect(items).toHaveCount(2)
  // 오래된 순: 내 쪽지(오른쪽) → 상대 쪽지(왼쪽)
  await expect(items.nth(0)).toHaveClass(/msg-mine/)
  await expect(items.nth(0)).toContainText('안녕하세요, 아직 있나요?')
  await expect(items.nth(1)).toHaveClass(/msg-theirs/)
  await expect(items.nth(1)).toContainText('아직 있어요')
  // 내 말풍선이 상대 말풍선보다 화면 오른쪽에 있다
  const mine = await items.nth(0).locator('.msg-bubble').boundingBox()
  const theirs = await items.nth(1).locator('.msg-bubble').boundingBox()
  expect(mine!.x).toBeGreaterThan(theirs!.x)
})

test('답장을 보내면 목록 끝에 붙고 입력칸이 비워진다', async ({ page, api }) => {
  loginVerified(api)
  const calls = record(page, 'POST', '/api/conversations/3/messages')
  await page.goto('/messages/3')
  await page.getByLabel('답장 내용').fill('답장입니다')
  await page.getByRole('button', { name: '답장 보내기' }).click()

  const items = page.locator('.msg')
  await expect(items).toHaveCount(3)
  await expect(items.nth(2)).toHaveClass(/msg-mine/)
  await expect(items.nth(2)).toContainText('답장입니다')
  await expect(page.getByLabel('답장 내용')).toHaveValue('')
  await expect(page.getByLabel('답장 내용')).toBeFocused() // 키보드 사용자가 이어서 쓸 수 있다
  expect(JSON.parse(calls[0].body ?? '{}')).toEqual({ content: '답장입니다' })
})

test('답장 칸도 비었거나 공백뿐이면 보내기가 비활성이다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/messages/3')
  await expect(page.getByRole('button', { name: '답장 보내기' })).toBeDisabled()
  await page.getByLabel('답장 내용').fill('  ')
  await expect(page.getByRole('button', { name: '답장 보내기' })).toBeDisabled()
})

test('상대가 탈퇴한 대화는 "탈퇴한 회원"과 안내가 보이고 답장 칸이 없다', async ({ page, api }) => {
  loginVerified(api)
  const detail: ConversationDetail = { ...fakeConversationDetail, partner: null }
  api.override({ 'GET /conversations/3': { status: 200, body: detail } })
  await page.goto('/messages/3')
  await expect(page.getByRole('heading', { name: '탈퇴한 회원' })).toBeVisible()
  await expect(page.getByText('상대가 탈퇴해 더 이상 쪽지를 보낼 수 없어요')).toBeVisible()
  await expect(page.getByLabel('답장 내용')).toHaveCount(0)
  // 지난 쪽지는 그대로 읽을 수 있다
  await expect(page.locator('.msg')).toHaveCount(2)
})

test('글이 지워진 대화는 글 제목에 링크가 없고 "삭제된 글" 표시가 붙는다', async ({ page, api }) => {
  loginVerified(api)
  const detail: ConversationDetail = { ...fakeConversationDetail, item: { id: null, title: '지워진 책상', deleted: true } }
  api.override({ 'GET /conversations/3': { status: 200, body: detail } })
  await page.goto('/messages/3')
  await expect(page.getByText('지워진 책상')).toBeVisible()
  await expect(page.getByText('삭제된 글')).toBeVisible()
  await expect(page.getByRole('link', { name: '지워진 책상' })).toHaveCount(0)
})

test('학교 인증 전 회원은 대화는 읽을 수 있고 답장 칸 대신 안내가 보인다', async ({ page, api }) => {
  api.setLoggedIn(true)
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect(page.getByText('학교 인증 후 답장할 수 있어요')).toBeVisible()
  await expect(page.getByLabel('답장 내용')).toHaveCount(0)
})

for (const [status, text] of [
  [400, '쪽지 내용을 입력해 주세요 (최대 1000자)'],
  [403, '쪽지를 보내지 못했습니다. 새로고침 후 다시 시도해 주세요'],
  [404, '대화를 찾을 수 없습니다'],
  [409, '상대가 탈퇴해 쪽지를 보낼 수 없습니다'],
  [500, '쪽지를 보내지 못했습니다'],
] as const) {
  test(`답장이 ${status}이면 "${text}"가 보이고 쓴 내용은 남는다`, async ({ page, api }) => {
    loginVerified(api)
    api.override({ 'POST /conversations/3/messages': { status } })
    await page.goto('/messages/3')
    await page.getByLabel('답장 내용').fill('답장입니다')
    await page.getByRole('button', { name: '답장 보내기' }).click()
    await expect(page.getByRole('alert')).toHaveText(text)
    await expect(page.getByLabel('답장 내용')).toHaveValue('답장입니다')
    await expect(page.locator('.msg')).toHaveCount(2) // 실패하면 목록에 안 붙는다
  })
}

test('남의 방이거나 없는 방(404)이면 "대화를 찾을 수 없습니다"가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/9': { status: 404 } })
  await page.goto('/messages/9')
  await expect(page.getByRole('alert')).toHaveText('대화를 찾을 수 없습니다')
  await expect(page.getByRole('link', { name: '쪽지함으로' })).toBeVisible()
})

test('대화 불러오기가 500이면 기본 실패 문구가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/3': { status: 500 } })
  await page.goto('/messages/3')
  await expect(page.getByRole('alert')).toHaveText('대화를 불러오지 못했습니다')
})

test('375px에서 긴 쪽지가 있어도 가로 스크롤이 없다', async ({ page, api }) => {
  await page.setViewportSize({ width: 375, height: 800 })
  loginVerified(api)
  const detail: ConversationDetail = {
    ...fakeConversationDetail,
    messages: [{ id: 1, content: '긴쪽지'.repeat(200), createdAt: '2026-01-02T03:00:00Z', mine: false }],
  }
  api.override({ 'GET /conversations/3': { status: 200, body: detail } })
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(1)
  expect(await hasHorizontalScroll(page)).toBe(false)
})
