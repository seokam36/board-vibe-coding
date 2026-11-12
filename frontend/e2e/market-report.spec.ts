import { test, expect, loginVerified, fakeUser } from './mockApi.ts'
import type { MarketItem, Report, ReportReason } from '../src/types.ts'

// S13-e: 중고 글 신고 화면. 서버 응답은 mockApi 가짜 응답이다.
// 확인할 것: (1) 남의 글 상세에서 신고 → 올바른 주소로 POST (2) 본인 글·비로그인에는 신고 버튼 없음 (3) 관리자 목록의 "중고" 배지와 링크

const school = { id: 1, name: '가짜대학교' }
const fakeReasons: ReportReason[] = [
  { code: 'SPAM', name: '스팸' },
  { code: 'ABUSE', name: '욕설' },
]

// 가짜 중고 글. 판매자 id가 1이면 "내 글"(로그인 가짜 회원 id = 1)
function makeItem(sellerId: number): MarketItem {
  return {
    id: 1,
    title: '가짜 자전거',
    content: '거의 새 자전거입니다.',
    tradeType: { code: 'SALE', name: '판매' },
    price: 5000,
    status: { code: 'ON_SALE', name: '판매중' },
    school,
    seller: { id: sellerId, nickname: sellerId === 1 ? fakeUser.nickname : '다른사람' },
    createdAt: '2026-01-02T00:00:00Z',
    photos: [],
  }
}

// 신고 POST가 나간 요청을 모은다 (주소·본문 확인용)
function recordReports(page: import('@playwright/test').Page) {
  const calls: { path: string; body: string | null }[] = []
  page.on('request', (r) => {
    const path = new URL(r.url()).pathname
    if (r.method() === 'POST' && path.endsWith('/reports')) calls.push({ path, body: r.postData() })
  })
  return calls
}

test('같은 학교 인증 회원이 남의 중고 글에서 신고하면 POST /market-items/1/reports가 나가고 성공 문구가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem(5) },
    'GET /report-reasons': { status: 200, body: fakeReasons },
    'POST /market-items/1/reports': { status: 201, body: { id: 9 } },
  })
  const calls = recordReports(page)
  await page.goto('/market/1')
  await page.getByRole('button', { name: '중고 글 신고' }).click()
  await page.getByLabel('신고 사유').selectOption('SPAM')
  await page.getByRole('button', { name: '신고 보내기' }).click()

  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  // 주소에 글 id가 빠지거나 다른 곳으로 나가면 안 된다 (정확히 /api/market-items/1/reports)
  expect(calls.map((c) => c.path)).toEqual(['/api/market-items/1/reports'])
  expect(JSON.parse(calls[0].body ?? '{}')).toEqual({ reason: 'SPAM' })
})

test('중고 글 신고가 409면 "이미 신고한 중고 글입니다"가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem(5) },
    'GET /report-reasons': { status: 200, body: fakeReasons },
    'POST /market-items/1/reports': { status: 409 },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '중고 글 신고' }).click()
  await page.getByLabel('신고 사유').selectOption('ABUSE')
  await page.getByRole('button', { name: '신고 보내기' }).click()

  await expect(page.getByRole('alert')).toHaveText('이미 신고한 중고 글입니다')
})

test('중고 글 신고가 404면 "이미 삭제된 중고 글입니다"가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem(5) },
    'GET /report-reasons': { status: 200, body: fakeReasons },
    'POST /market-items/1/reports': { status: 404 },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '중고 글 신고' }).click()
  await page.getByLabel('신고 사유').selectOption('SPAM')
  await page.getByRole('button', { name: '신고 보내기' }).click()

  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 중고 글입니다')
})

test('본인 중고 글에는 신고 버튼이 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem(1) } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('button', { name: '중고 글 신고' })).toHaveCount(0)
})

test('비로그인이면 중고 글 상세에 신고 버튼이 없다', async ({ page, api }) => {
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem(5) } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('button', { name: '중고 글 신고' })).toHaveCount(0)
})

test('학교 인증을 안 한 회원에게는 중고 글 신고 버튼이 없다', async ({ page, api }) => {
  api.setLoggedIn(true) // 로그인만 하고 학교 인증은 없음
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem(5) } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('button', { name: '중고 글 신고' })).toHaveCount(0)
})

// 다른 학교로 인증한 회원: 글 학교(id 1)와 인증 학교(id 2)가 달라서 신고 버튼이 없어야 한다
test('다른 학교로 인증한 회원에게는 중고 글 상세에 신고 버튼이 없다', async ({ page, api }) => {
  api.setLoggedIn(true)
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, verifiedSchool: { id: 2, name: '다른대학교' } } },
    'GET /market-items/1': { status: 200, body: makeItem(5) },
  })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('button', { name: '중고 글 신고' })).toHaveCount(0)
})

// 관리자가 중고 글 신고에서 [삭제] -> 확인 창 -> DELETE /market-items/42 (게시판 /posts/ 주소가 아니어야 한다)
test('관리자 신고 관리에서 중고 글 신고를 삭제하면 DELETE /market-items/42가 나가고 /posts는 안 나간다', async ({ page, api }) => {
  api.setLoggedIn(true)
  const report: Report = {
    id: 3,
    target: { type: 'MARKET_ITEM', id: 42, postId: null, preview: '가짜 자전거', author: { id: 5, nickname: '다른사람' } },
    reporter: { id: 6, nickname: '신고자' },
    reason: { code: 'SPAM', name: '스팸' },
    detail: null,
    status: 'PENDING',
    createdAt: '2026-01-02T00:00:00Z',
  }
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN' } },
    'GET /admin/reports': { status: 200, body: [report] },
    'DELETE /market-items/42': { status: 204 },
  })
  // 나간 DELETE 요청의 경로를 모은다
  const deletes: string[] = []
  page.on('request', (r) => {
    if (r.method() === 'DELETE') deletes.push(new URL(r.url()).pathname)
  })
  await page.goto('/admin/reports')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByText('처리했습니다')).toBeVisible()
  expect(deletes).toEqual(['/api/market-items/42'])
  expect(deletes.some((p) => p.includes('/posts/'))).toBe(false)
})

test('관리자 신고 관리에서 중고 글 신고는 "중고" 배지와 /market/{id} 링크로 보인다', async ({ page, api }) => {
  api.setLoggedIn(true)
  const report: Report = {
    id: 3,
    target: {
      type: 'MARKET_ITEM',
      id: 42,
      postId: null,// 실제 응답은 postId가 null이다
      preview: '가짜 자전거',
      author: { id: 5, nickname: '다른사람' },
    },
    reporter: { id: 6, nickname: '신고자' },
    reason: { code: 'SPAM', name: '스팸' },
    detail: null,
    status: 'PENDING',
    createdAt: '2026-01-02T00:00:00Z',
  }
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN' } },
    'GET /admin/reports': { status: 200, body: [report] },
  })
  await page.goto('/admin/reports')

  const row = page.locator('.row')
  await expect(row).toHaveCount(1)
  await expect(row.locator('.badge')).toHaveText('중고')
  await expect(row.getByRole('link', { name: '글로 가기' })).toHaveAttribute('href', '/market/42')
  await expect(row).toContainText('가짜 자전거')
})
