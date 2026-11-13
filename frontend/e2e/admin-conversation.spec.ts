import { test, expect, loginAdmin } from './mockApi.ts'
import type { AdminConversationView, Report } from '../src/types.ts'

// S14-f4: 관리자 신고 목록의 "쪽지" 배지 + 신고된 대화 보기 화면. 서버 응답은 mockApi 가짜 응답이다.

const ADMIN_ONLY = '관리자만 볼 수 있습니다'
const NOT_CONVERSATION = '없는 신고이거나 대화 신고가 아닙니다'
const LOAD_FAILED = '대화를 불러오지 못했습니다'
const RELOGIN = '다시 로그인해 주세요 로그인' // 안내 문구 + 로그인 링크 글자
const INFO = '관리자가 신고된 대화를 열람 중입니다. 읽기만 할 수 있어요'

// 시각 표기가 "올해냐 아니냐"로 달라지니 브라우저 시계를 고정한다 (2026-11-13 12:00 서울)
test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-13T03:00:00Z'))
})

const reason = { code: 'SPAM', name: '스팸' }

// 신고 id 7 = 대화방 id 12 (일부러 다른 숫자: 링크는 신고 id를 써야 한다)
const conversationReport: Report = {
  id: 7,
  target: { type: 'CONVERSATION', id: 12, postId: null, preview: '중고 자전거 팝니다', author: { id: 8, nickname: '판매자' } },
  reporter: { id: 2, nickname: '신고자' },
  reason,
  detail: '이상한 말을 해요',
  status: 'PENDING',
  createdAt: '2026-11-13T00:00:00Z',
}
const postReport: Report = {
  id: 1,
  target: { type: 'POST', id: 5, postId: 5, preview: '신고된 글 내용', author: { id: 3, nickname: '글쓴이' } },
  reporter: { id: 2, nickname: '신고자' },
  reason,
  detail: null,
  status: 'PENDING',
  createdAt: '2026-11-12T00:00:00Z',
}
const commentReport: Report = {
  id: 2,
  target: { type: 'COMMENT', id: 9, postId: 5, preview: '신고된 댓글 내용', author: { id: 3, nickname: '글쓴이' } },
  reporter: { id: 2, nickname: '신고자' },
  reason,
  detail: null,
  status: 'PENDING',
  createdAt: '2026-11-12T00:00:00Z',
}
const marketReport: Report = {
  id: 3,
  target: { type: 'MARKET_ITEM', id: 42, postId: null, preview: '신고된 중고 글', author: { id: 5, nickname: '다른사람' } },
  reporter: { id: 2, nickname: '신고자' },
  reason,
  detail: null,
  status: 'PENDING',
  createdAt: '2026-11-12T00:00:00Z',
}

// 신고 7번의 대화: 구매자 신고자(2), 판매자 판매자(8)
const view: AdminConversationView = {
  reportId: 7,
  conversationId: 12,
  itemTitle: '중고 자전거 팝니다',
  buyer: { id: 2, nickname: '신고자' },
  seller: { id: 8, nickname: '판매자' },
  messages: [
    { id: 30, sender: { id: 2, nickname: '신고자' }, content: '아직 있나요?', createdAt: '2026-11-13T03:00:00Z' },
    { id: 31, sender: { id: 8, nickname: '판매자' }, content: '네 있어요', createdAt: '2026-11-13T03:05:00Z' },
  ],
}

// ---- 신고 목록 ----

test('목록에서 대화 신고는 "쪽지" 배지, 신고 id로 가는 [대화 보기] 링크, [문제 없음]만 있고 [삭제]는 없다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports': { status: 200, body: [conversationReport] } })
  await page.goto('/admin/reports')

  const row = page.locator('.row')
  await expect(row).toHaveCount(1)
  await expect(row.locator('.badge')).toHaveText('쪽지')
  // 방 id(12)가 아니라 신고 id(7)
  await expect(row.getByRole('link', { name: '대화 보기' })).toHaveAttribute('href', '/admin/reports/7/conversation')
  await expect(row.getByRole('link', { name: '글로 가기' })).toHaveCount(0)
  await expect(row.getByRole('button', { name: '삭제' })).toHaveCount(0)
  await expect(row.getByRole('button', { name: '문제 없음' })).toHaveCount(1)
  await expect(row).toContainText('중고 자전거 팝니다')
  await expect(row).toContainText('판매자') // 대화 상대
})

test('대화 신고를 [문제 없음]으로 기각하면 PUT /admin/reports/7/dismiss가 나가고 목록에서 사라진다', async ({ page, api }) => {
  loginAdmin(api)
  let dismissed = false
  api.override({
    'GET /admin/reports': () => ({ status: 200, body: dismissed ? [] : [conversationReport] }),
    'PUT /admin/reports/7/dismiss': () => {
      dismissed = true
      return { status: 200, body: { ...conversationReport, status: 'DISMISSED' } }
    },
  })
  const puts: string[] = []
  page.on('request', (r) => {
    if (r.method() === 'PUT') puts.push(new URL(r.url()).pathname)
  })
  await page.goto('/admin/reports')
  await page.getByRole('button', { name: '문제 없음' }).click()

  await expect(page.getByText('처리했습니다')).toBeVisible()
  await expect(page.locator('.row')).toHaveCount(0)
  expect(puts).toEqual(['/api/admin/reports/7/dismiss'])
})

test('글·댓글·중고 글·대화 신고가 섞여도 각자 배지·링크·버튼이 맞다 (회귀)', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports': { status: 200, body: [postReport, commentReport, marketReport, conversationReport] } })
  await page.goto('/admin/reports')

  const rows = page.locator('.row')
  await expect(rows).toHaveCount(4)
  await expect(rows.nth(0).locator('.badge')).toHaveText('글')
  await expect(rows.nth(0).getByRole('link', { name: '글로 가기' })).toHaveAttribute('href', '/posts/5')
  await expect(rows.nth(1).locator('.badge')).toHaveText('댓글')
  await expect(rows.nth(1).getByRole('link', { name: '글로 가기' })).toHaveAttribute('href', '/posts/5')
  await expect(rows.nth(2).locator('.badge')).toHaveText('중고')
  await expect(rows.nth(2).getByRole('link', { name: '글로 가기' })).toHaveAttribute('href', '/market/42')
  await expect(rows.nth(3).locator('.badge')).toHaveText('쪽지')
  // 글·댓글·중고 글은 [삭제]가 그대로 있고, 대화만 없다
  for (const i of [0, 1, 2]) await expect(rows.nth(i).getByRole('button', { name: '삭제' })).toHaveCount(1)
  await expect(rows.nth(3).getByRole('button', { name: '삭제' })).toHaveCount(0)
})

test('목록에서 [대화 보기]를 누르면 대화 보기 화면으로 간다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports': { status: 200, body: [conversationReport] },
    'GET /admin/reports/7/conversation': { status: 200, body: view },
  })
  await page.goto('/admin/reports')
  await page.getByRole('link', { name: '대화 보기' }).click()
  await expect(page).toHaveURL(/\/admin\/reports\/7\/conversation$/)
  await expect(page.getByRole('heading', { name: '중고 자전거 팝니다' })).toBeVisible()
})

// ---- 대화 보기 화면 ----

test('대화 보기: 안내·제목·구매자·판매자·쪽지 내용과 시각이 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { status: 200, body: view } })
  await page.goto('/admin/reports/7/conversation')

  await expect(page.getByRole('status').filter({ hasText: '열람 중' })).toHaveText(INFO)
  await expect(page.getByRole('heading', { name: '중고 자전거 팝니다' })).toBeVisible()
  const meta = page.locator('.report-meta')
  await expect(meta.locator('div').nth(0)).toHaveText('구매자신고자')
  await expect(meta.locator('div').nth(1)).toHaveText('판매자판매자')

  const msgs = page.locator('.msg')
  await expect(msgs).toHaveCount(2)
  await expect(msgs.nth(0)).toContainText('신고자 · 구매자')
  await expect(msgs.nth(0)).toContainText('아직 있나요?')
  await expect(msgs.nth(0).locator('.msg-time')).toHaveText('11월 13일 12:00')
  await expect(msgs.nth(1)).toContainText('판매자 · 판매자')
  await expect(msgs.nth(1)).toContainText('네 있어요')
  await expect(msgs.nth(1).locator('.msg-time')).toHaveText('11월 13일 12:05')
  // 구매자는 왼쪽, 판매자는 오른쪽 말풍선
  await expect(msgs.nth(0)).toHaveClass(/msg-theirs/)
  await expect(msgs.nth(1)).toHaveClass(/msg-mine/)
})

test('대화 보기는 읽기 전용이다: 답장 칸·보내기·신고 버튼이 없다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { status: 200, body: view } })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.locator('.msg')).toHaveCount(2)

  await expect(page.locator('textarea')).toHaveCount(0)
  await expect(page.getByLabel('답장 내용')).toHaveCount(0)
  await expect(page.getByRole('button', { name: '답장 보내기' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '대화 신고' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /신고/ })).toHaveCount(0)
})

test('탈퇴한 구매자·판매자는 "탈퇴한 회원"으로, 보낸 사람이 null인 쪽지도 "탈퇴한 회원"으로 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': {
      status: 200,
      body: {
        ...view,
        buyer: null,
        seller: null,
        messages: [{ id: 40, sender: null, content: '탈퇴한 사람이 보낸 쪽지', createdAt: '2026-11-13T03:00:00Z' }],
      } satisfies AdminConversationView,
    },
  })
  await page.goto('/admin/reports/7/conversation')

  const meta = page.locator('.report-meta')
  await expect(meta.locator('div').nth(0)).toHaveText('구매자탈퇴한 회원')
  await expect(meta.locator('div').nth(1)).toHaveText('판매자탈퇴한 회원')
  const msg = page.locator('.msg')
  await expect(msg).toHaveCount(1)
  await expect(msg.locator('.msg-sender')).toHaveText('탈퇴한 회원') // 구매자/판매자 표시는 붙이지 않는다
  await expect(msg).toContainText('탈퇴한 사람이 보낸 쪽지')
})

test('구매자만 탈퇴했어도 판매자가 보낸 쪽지에는 판매자 표시가 붙는다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': {
      status: 200,
      body: { ...view, buyer: null, messages: [view.messages[1]] } satisfies AdminConversationView,
    },
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.locator('.msg-sender')).toHaveText('판매자 · 판매자')
})

test('쪽지가 0개면 "아직 쪽지가 없습니다"가 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { status: 200, body: { ...view, messages: [] } } })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByText('아직 쪽지가 없습니다')).toBeVisible()
  await expect(page.locator('.msg')).toHaveCount(0)
})

test('줄바꿈은 그대로, 아주 긴 주소도 화면 밖으로 넘치지 않는다', async ({ page, api }) => {
  loginAdmin(api)
  const longUrl = 'https://example.com/' + 'a'.repeat(300)
  api.override({
    'GET /admin/reports/7/conversation': {
      status: 200,
      body: {
        ...view,
        messages: [{ id: 50, sender: view.buyer, content: `첫 줄\n둘째 줄\n${longUrl}`, createdAt: '2026-11-13T03:00:00Z' }],
      } satisfies AdminConversationView,
    },
  })
  await page.goto('/admin/reports/7/conversation')

  const bubble = page.locator('.msg-bubble')
  await expect(bubble).toHaveText(`첫 줄\n둘째 줄\n${longUrl}`)
  // 말풍선이 화면보다 넓어지지 않고, 페이지에 가로 스크롤이 생기지 않는다
  const viewport = page.viewportSize()!
  const box = (await bubble.boundingBox())!
  expect(box.x + box.width).toBeLessThanOrEqual(viewport.width)
  // (e2e 타입 검사에는 브라우저 타입이 없어서 코드를 글자로 넘긴다)
  const overflow = await page.evaluate('document.documentElement.scrollWidth > document.documentElement.clientWidth')
  expect(overflow).toBe(false)
})

test('쪽지 안의 HTML 글자는 태그로 실행되지 않고 글자 그대로 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': {
      status: 200,
      body: { ...view, messages: [{ id: 51, sender: view.buyer, content: '<b>굵게</b>', createdAt: '2026-11-13T03:00:00Z' }] },
    },
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.locator('.msg-bubble')).toHaveText('<b>굵게</b>')
  await expect(page.locator('.msg-bubble b')).toHaveCount(0)
})

test('기각된 신고의 대화도 읽을 수 있고 [신고 목록으로]를 누르면 목록으로 돌아간다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': { status: 200, body: view },
    'GET /admin/reports': { status: 200, body: [] },
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.locator('.msg')).toHaveCount(2)
  await page.getByRole('link', { name: '신고 목록으로' }).click()
  await expect(page).toHaveURL(/\/admin\/reports$/)
  await expect(page.getByRole('heading', { name: '신고 관리' })).toBeVisible()
})

// ---- 실패·권한 ----

test('403이면 "관리자만 볼 수 있습니다"가 보이고 다시 시도 버튼은 없다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { status: 403 } })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByRole('alert')).toHaveText(ADMIN_ONLY)
  await expect(page.getByRole('button', { name: '다시 시도' })).toHaveCount(0)
  await expect(page.getByText('열람 중')).toHaveCount(0) // 오류 때는 "열람 중" 안내가 없다
})

test('404면 "없는 신고이거나 대화 신고가 아닙니다"가 보이고 [신고 목록으로]는 남아 있다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { status: 404 } })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByRole('alert')).toHaveText(NOT_CONVERSATION)
  await expect(page.getByRole('link', { name: '신고 목록으로' })).toBeVisible()
  await expect(page.getByRole('button', { name: '다시 시도' })).toHaveCount(0)
})

test('주소의 신고 번호가 숫자가 아니라서 400이면 같은 안내가 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/abc/conversation': { status: 400 } })
  await page.goto('/admin/reports/abc/conversation')
  await expect(page.getByRole('alert')).toHaveText(NOT_CONVERSATION)
})

test('401이고 재발급도 401이면 다시 로그인 안내만 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
})

test('서버 오류(500)면 기본 실패 문구와 [다시 시도]가 보이고, 누르면 다시 불러온다', async ({ page, api }) => {
  loginAdmin(api)
  let fail = true
  api.override({
    'GET /admin/reports/7/conversation': () => (fail ? { status: 500 } : { status: 200, body: view }),
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByRole('alert')).toHaveText(`${LOAD_FAILED} 다시 시도`) // 문구 + [다시 시도] 버튼 글자
  fail = false
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('네트워크가 끊기면 "서버에 연결할 수 없습니다"와 [다시 시도]가 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { abort: true } })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByRole('alert')).toHaveText('서버에 연결할 수 없습니다 다시 시도') // 문구 + [다시 시도] 버튼 글자
  await expect(page.getByRole('button', { name: '다시 시도' })).toBeVisible()
})

test('일반 회원이 주소로 직접 들어오면 안내만 보이고 대화 요청은 보내지 않는다', async ({ page, api }) => {
  api.setLoggedIn(true) // role USER
  const calls: string[] = []
  page.on('request', (r) => {
    if (new URL(r.url()).pathname.startsWith('/api/admin/')) calls.push(r.url()) // 관리자 API 요청만
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByRole('alert')).toHaveText(ADMIN_ONLY)
  await expect(page.locator('.msg')).toHaveCount(0)
  expect(calls).toEqual([])
})

test('비로그인으로 들어와도 안내만 보이고 대화 요청은 보내지 않는다 (신고 목록 화면과 같은 방식)', async ({ page, api }) => {
  const calls: string[] = []
  page.on('request', (r) => {
    if (new URL(r.url()).pathname.startsWith('/api/admin/')) calls.push(r.url()) // 관리자 API 요청만
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByRole('alert')).toHaveText(ADMIN_ONLY)
  expect(calls).toEqual([])
  expect(api.unhandled).toEqual([])
})
