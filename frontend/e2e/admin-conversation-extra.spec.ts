import { test, expect, loginAdmin } from './mockApi.ts'
import type { AdminConversationView, Report } from '../src/types.ts'

// S14-f4 빈틈 보강: 기각 실패, 기각된 탭의 링크, 모르는 보낸 사람, 많은 쪽지, 번호 이동, 쓰기 요청 없음.

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-13T03:00:00Z'))
})

const GONE = '이미 처리됐거나 사라진 신고입니다'
const reason = { code: 'SPAM', name: '스팸' }
function convReport(id: number, status: Report['status'] = 'PENDING'): Report {
  return {
    id,
    target: { type: 'CONVERSATION', id: 100 + id, postId: null, preview: `제목${id}`, author: { id: 8, nickname: '판매자' } },
    reporter: { id: 2, nickname: '신고자' },
    reason,
    detail: null,
    status,
    createdAt: '2026-11-13T00:00:00Z',
  }
}
function viewOf(reportId: number, title: string, messages: AdminConversationView['messages'] = []): AdminConversationView {
  return {
    reportId,
    conversationId: 100 + reportId,
    itemTitle: title,
    buyer: { id: 2, nickname: '신고자' },
    seller: { id: 8, nickname: '판매자' },
    messages,
    hasMore: false,
  }
}

test('대화 신고 기각 중 404가 나면 안내 후 목록을 다시 읽어 맞춘다', async ({ page, api }) => {
  loginAdmin(api)
  let gone = false
  api.override({
    'GET /admin/reports': () => ({ status: 200, body: gone ? [] : [convReport(7)] }),
    'PUT /admin/reports/7/dismiss': () => {
      gone = true
      return { status: 404 }
    },
  })
  await page.goto('/admin/reports')
  await page.getByRole('button', { name: '문제 없음' }).click()
  await expect(page.locator('.row')).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveText(GONE)
})

test('대화 신고 기각 중 409(이미 처리됨)가 나도 목록이 다시 맞춰진다', async ({ page, api }) => {
  loginAdmin(api)
  let gone = false
  api.override({
    'GET /admin/reports': () => ({ status: 200, body: gone ? [] : [convReport(7)] }),
    'PUT /admin/reports/7/dismiss': () => {
      gone = true
      return { status: 409 }
    },
  })
  await page.goto('/admin/reports')
  await page.getByRole('button', { name: '문제 없음' }).click()
  await expect(page.locator('.row')).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveText(GONE)
})

test('대화 신고가 목록 맨 위에 있어도 각 행의 [대화 보기]는 자기 신고 번호를 쓴다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports': { status: 200, body: [convReport(9), convReport(4), convReport(6)] } })
  await page.goto('/admin/reports')
  const links = page.getByRole('link', { name: '대화 보기' })
  await expect(links).toHaveCount(3)
  await expect(links.nth(0)).toHaveAttribute('href', '/admin/reports/9/conversation')
  await expect(links.nth(1)).toHaveAttribute('href', '/admin/reports/4/conversation')
  await expect(links.nth(2)).toHaveAttribute('href', '/admin/reports/6/conversation')
})

test('처리된 탭(기각됨)의 대화 신고에도 [대화 보기] 링크가 있고 [문제 없음]은 없다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports': { status: 200, body: [convReport(7, 'DISMISSED')] } })
  await page.goto('/admin/reports?status=DISMISSED')
  const row = page.locator('.row')
  await expect(row).toHaveCount(1)
  await expect(row.getByRole('link', { name: '대화 보기' })).toHaveAttribute('href', '/admin/reports/7/conversation')
  await expect(row.getByRole('button', { name: '문제 없음' })).toHaveCount(0)
})

test('보낸 사람이 구매자도 판매자도 아니면 이름만 보이고 역할 표시는 없다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': {
      status: 200,
      body: viewOf(7, '제목7', [{ id: 1, sender: { id: 99, nickname: '낯선사람' }, content: '안녕', createdAt: '2026-11-13T03:00:00Z' }]),
    },
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.locator('.msg-sender')).toHaveText('낯선사람')
})

test('닉네임이 같아도 id로 구분한다: 구매자 id와 같은 sender만 구매자로 표시', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': {
      status: 200,
      body: viewOf(7, '제목7', [{ id: 1, sender: { id: 77, nickname: '신고자' }, content: '동명이인', createdAt: '2026-11-13T03:00:00Z' }]),
    },
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.locator('.msg-sender')).toHaveText('신고자') // id가 다르니 "· 구매자"가 붙지 않는다
})

test('쪽지 500개도 순서대로 모두 렌더링된다', async ({ page, api }) => {
  loginAdmin(api)
  const messages: AdminConversationView['messages'] = Array.from({ length: 500 }, (_, i) => ({
    id: i + 1,
    sender: i % 2 === 0 ? { id: 2, nickname: '신고자' } : { id: 8, nickname: '판매자' },
    content: `메시지 ${i + 1}`,
    createdAt: '2026-11-13T03:00:00Z',
  }))
  api.override({ 'GET /admin/reports/7/conversation': { status: 200, body: viewOf(7, '제목7', messages) } })
  await page.goto('/admin/reports/7/conversation')
  const msgs = page.locator('.msg')
  await expect(msgs).toHaveCount(500)
  await expect(msgs.first()).toContainText('메시지 1')
  await expect(msgs.last()).toContainText('메시지 500')
})

test('주소의 신고 번호가 0이나 음수여도 서버가 400/404를 주면 같은 안내가 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/0/conversation': { status: 404 },
    'GET /admin/reports/-5/conversation': { status: 400 },
  })
  await page.goto('/admin/reports/0/conversation')
  await expect(page.getByRole('alert')).toHaveText('없는 신고이거나 대화 신고가 아닙니다')
  await page.goto('/admin/reports/-5/conversation')
  await expect(page.getByRole('alert')).toHaveText('없는 신고이거나 대화 신고가 아닙니다')
})

test('신고 번호가 바뀌면 새 응답이 오기 전에 옛 대화는 사라지고 "불러오는 중"이 보인다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': {
      status: 200,
      body: viewOf(7, '제목7', [{ id: 1, sender: { id: 2, nickname: '신고자' }, content: '일곱번째 대화', createdAt: '2026-11-13T03:00:00Z' }]),
    },
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByText('일곱번째 대화')).toBeVisible()

  // 8번 응답을 붙잡아 둔다 (오기 전의 화면을 보려고)
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => (release = resolve))
  await page.route('**/api/admin/reports/8/conversation', async (route) => {
    await gate
    await route.fulfill({
      status: 200,
      json: viewOf(8, '제목8', [{ id: 2, sender: { id: 2, nickname: '신고자' }, content: '여덟번째 대화', createdAt: '2026-11-13T03:00:00Z' }]),
    })
  })
  // 앱 안에서 주소만 8번으로 바꾼다 (새로고침 없이)
  await page.evaluate("window.history.pushState({}, '', '/admin/reports/8/conversation'); window.dispatchEvent(new PopStateEvent('popstate'))")
  // 응답이 오기 전: 옛 대화(제목·본문)는 없고 로딩만 보인다
  await expect(page.getByText('불러오는 중')).toBeVisible()
  await expect(page.getByText('일곱번째 대화')).toHaveCount(0)
  await expect(page.getByRole('heading', { name: '제목7' })).toHaveCount(0)
  release()

  // 응답이 오면 8번 내용만 보인다
  await expect(page.getByRole('heading', { name: '제목8' })).toBeVisible()
  await expect(page.getByText('여덟번째 대화')).toBeVisible()
  await expect(page.getByText('일곱번째 대화')).toHaveCount(0)
})

test('키보드 Tab으로 [신고 목록으로] 링크에 닿고 Enter로 목록에 간다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': { status: 200, body: viewOf(7, '제목7') },
    'GET /admin/reports': { status: 200, body: [] },
  })
  await page.goto('/admin/reports/7/conversation')
  const back = page.getByRole('link', { name: '신고 목록으로' })
  await back.focus()
  await expect(back).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/admin\/reports$/)
})

test('대화 보기 화면에서는 GET 말고 어떤 쓰기 요청도 나가지 않는다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({
    'GET /admin/reports/7/conversation': {
      status: 200,
      body: viewOf(7, '제목7', [{ id: 1, sender: { id: 2, nickname: '신고자' }, content: '안녕', createdAt: '2026-11-13T03:00:00Z' }]),
    },
  })
  const writes: string[] = []
  page.on('request', (r) => {
    if (!['GET', 'HEAD', 'OPTIONS'].includes(r.method()) && new URL(r.url()).pathname.startsWith('/api/')) {
      writes.push(`${r.method()} ${new URL(r.url()).pathname}`)
    }
  })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.locator('.msg')).toHaveCount(1)
  await page.locator('.msg-bubble').click()
  await page.keyboard.press('Enter')
  expect(writes).toEqual([])
})
