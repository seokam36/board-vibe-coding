import { test, expect, loginVerified, fakeUser } from './mockApi.ts'
import type { Page } from '@playwright/test'
import type { MarketItem } from '../src/types.ts'

// S13-d: 중고거래·나눔 화면(/market 목록·상세·글쓰기·수정). 서버 응답은 mockApi 가짜 응답이다

const school = { id: 1, name: '가짜대학교' }
const PRICE_RANGE_MESSAGE = '판매 가격은 1원 이상 10,000,000원 이하의 숫자로 입력해 주세요'
const RELOGIN_TEXT = '다시 로그인해 주세요 로그인' // 안내 문구 + 로그인 링크 글자
const STATUS_NAMES = { ON_SALE: '판매중', RESERVED: '예약중', DONE: '거래완료' } as const

// 가짜 글 만들기. 기본은 "내(id 1) 판매중 판매글 5,000원"
function makeItem(over: Partial<{ id: number; sellerId: number; status: MarketItem['status']['code']; free: boolean; title: string }> = {}): MarketItem {
  const status = over.status ?? 'ON_SALE'
  const free = over.free ?? false
  return {
    id: over.id ?? 1,
    title: over.title ?? '가짜 자전거',
    content: '거의 새 자전거입니다.',
    tradeType: free ? { code: 'FREE', name: '나눔' } : { code: 'SALE', name: '판매' },
    price: free ? 0 : 5000,
    status: { code: status, name: STATUS_NAMES[status] },
    school,
    seller: { id: over.sellerId ?? 1, nickname: over.sellerId && over.sellerId !== 1 ? '다른사람' : '테스터' },
    createdAt: '2026-01-02T00:00:00Z',
    photos: [],
  }
}

// 지정한 메서드·경로로 나간 요청을 모은다 (횟수·본문·주소 확인용)
function record(page: Page, method: string, pathname: string) {
  const calls: { url: string; body: string | null }[] = []
  page.on('request', (r) => {
    if (r.method() === method && new URL(r.url()).pathname === pathname) calls.push({ url: r.url(), body: r.postData() })
  })
  return calls
}

// 가로 스크롤이 생기는지: 문서 가로 폭이 화면 폭을 넘는지 본다
async function hasHorizontalScroll(page: Page) {
  return page.evaluate<boolean>('document.documentElement.scrollWidth > document.documentElement.clientWidth')
}

// ---- 목록 ----

test('상단바 "중고·나눔" 링크로 /market에 들어가면 가격·나눔·상태 뱃지·학교가 보인다', async ({ page, api }) => {
  api.override({
    'GET /market-items': { status: 200, body: [makeItem({ id: 2, title: '나눔 의자', free: true, status: 'RESERVED' }), makeItem({ id: 1 })] },
  })
  await page.goto('/')
  await page.getByRole('link', { name: '중고·나눔' }).click()
  await expect(page).toHaveURL(/\/market$/)
  const rows = page.locator('.row')
  await expect(rows).toHaveCount(2)
  // 첫 줄: 나눔 + 예약중 뱃지 / 둘째 줄: 5,000원 + 판매중 뱃지
  await expect(rows.nth(0)).toContainText('나눔 의자')
  await expect(rows.nth(0).locator('.market-price')).toHaveText('나눔')
  await expect(rows.nth(0).locator('.badge-RESERVED')).toHaveText('예약중')
  await expect(rows.nth(1).locator('.market-price')).toHaveText('5,000원')
  await expect(rows.nth(1).locator('.badge-ON_SALE')).toHaveText('판매중')
  await expect(rows.nth(1)).toContainText('가짜대학교')
})

test('목록이 비어 있으면 안내가 보인다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await page.goto('/market')
  await expect(page.getByText('아직 올라온 글이 없습니다')).toBeVisible()
})

test('필터를 고르면 주소 쿼리가 바뀌고 그 쿼리로 요청이 나가며 새로고침해도 유지된다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  const calls = record(page, 'GET', '/api/market-items')
  await page.goto('/market')
  await expect(page.locator('.row')).toHaveCount(1)
  await page.getByLabel('학교', { exact: true }).selectOption({ label: '가짜대학교' })
  await page.getByLabel('판매/나눔').selectOption('FREE')
  await page.getByLabel('상태').selectOption('DONE')
  await expect(page).toHaveURL(/schoolId=1/)
  await expect(page).toHaveURL(/tradeType=FREE/)
  await expect(page).toHaveURL(/status=DONE/)
  // 마지막 요청이 세 조건을 모두 담았는지
  await expect.poll(() => calls[calls.length - 1]?.url ?? '').toMatch(/(?=.*schoolId=1)(?=.*tradeType=FREE)(?=.*status=DONE)/)
  // 새로고침해도 select 값이 주소에서 복원된다
  // (새로고침 전에 나간 요청이 조건을 이미 담고 있어서 가짜로 통과하지 않도록, 새로고침 직전 요청 수를 기록해 둔다)
  const before = calls.length
  await page.reload()
  await expect(page.getByLabel('학교', { exact: true })).toHaveValue('1')
  await expect(page.getByLabel('판매/나눔')).toHaveValue('FREE')
  await expect(page.getByLabel('상태')).toHaveValue('DONE')
  // 새로고침 "뒤"에 새로 나간 GET 요청 중에 세 조건을 모두 담은 것이 있어야 한다
  await expect
    .poll(() =>
      calls.slice(before).some((c) => {
        const q = new URL(c.url).searchParams
        return q.get('schoolId') === '1' && q.get('tradeType') === 'FREE' && q.get('status') === 'DONE'
      }),
    )
    .toBe(true)
})

// ---- 상세 ----

test('상세 화면에 제목·내용·가격·상태·학교·판매자가 보인다', async ({ page, api }) => {
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem({ sellerId: 5 }) } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByText('거의 새 자전거입니다.')).toBeVisible()
  await expect(page.locator('.market-price')).toHaveText('5,000원')
  await expect(page.locator('.badge-ON_SALE')).toHaveText('판매중')
  await expect(page.locator('.badge-school')).toHaveText('가짜대학교')
  await expect(page.getByText('다른사람')).toBeVisible()
})

test('없는 글(404)이면 안내가 보인다', async ({ page, api }) => {
  api.override({ 'GET /market-items/999': { status: 404 } })
  await page.goto('/market/999')
  await expect(page.getByRole('alert')).toHaveText('글을 찾을 수 없습니다')
})

// ---- 글쓰기 버튼 / 안내 ----

test('비로그인이면 글쓰기 버튼 대신 로그인 안내가 보인다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await page.goto('/market')
  await expect(page.getByText('글을 올리려면 로그인이 필요해요')).toBeVisible()
  await expect(page.getByRole('link', { name: '글쓰기' })).toHaveCount(0)
})

test('미인증 회원이면 학교 인증 안내와 /me 링크가 보이고 글쓰기 버튼은 없다', async ({ page, api }) => {
  api.setLoggedIn(true)
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await page.goto('/market')
  await expect(page.getByText('중고·나눔 글은 학교 인증 후에 올릴 수 있어요')).toBeVisible()
  await expect(page.getByRole('link', { name: '내 정보에서 학교 인증하기' })).toHaveAttribute('href', '/me')
  await expect(page.getByRole('link', { name: '글쓰기' })).toHaveCount(0)
})

test('학교 인증 회원이면 글쓰기 버튼이 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await page.goto('/market')
  await expect(page.getByRole('link', { name: '글쓰기' })).toBeVisible()
  await expect(page.getByText('학교 인증 후에 올릴 수 있어요')).toHaveCount(0)
})

// ---- 글쓰기 ----

test('글쓰기에서 나눔을 고르면 가격 칸이 사라지고 POST 본문에 price가 없으며 성공하면 상세로 간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'POST /market-items': { status: 201, body: makeItem({ id: 7, free: true, title: '새 나눔' }) },
    'GET /market-items/7': { status: 200, body: makeItem({ id: 7, free: true, title: '새 나눔' }) },
  })
  const posts = record(page, 'POST', '/api/market-items')
  await page.goto('/market/new')
  await expect(page.getByLabel('가격(원)')).toBeVisible() // 처음은 판매라 가격 칸이 있다
  await page.getByLabel('판매/나눔').selectOption('FREE')
  await expect(page.getByLabel('가격(원)')).toHaveCount(0)
  await page.getByLabel('제목').fill('새 나눔')
  await page.getByLabel('본문').fill('가져가세요')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page).toHaveURL(/\/market\/7$/)
  expect(posts).toHaveLength(1)
  const body = JSON.parse(posts[0].body ?? '{}')
  expect(body).toEqual({ title: '새 나눔', content: '가져가세요', tradeType: 'FREE' })
  expect('price' in body).toBe(false)
})

test('판매에 가격 0을 넣으면 화면에서 막히고 요청이 나가지 않는다', async ({ page, api }) => {
  loginVerified(api)
  const posts = record(page, 'POST', '/api/market-items')
  await page.goto('/market/new')
  await page.getByLabel('제목').fill('비싼 물건')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('0')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText(PRICE_RANGE_MESSAGE)
  await expect(page).toHaveURL(/\/market\/new$/)
  expect(posts).toHaveLength(0)
})

test('판매 글쓰기는 가격을 숫자로 담아 POST하고 성공하면 상세로 간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'POST /market-items': { status: 201, body: makeItem({ id: 8 }) },
    'GET /market-items/8': { status: 200, body: makeItem({ id: 8 }) },
  })
  const posts = record(page, 'POST', '/api/market-items')
  await page.goto('/market/new')
  await page.getByLabel('제목').fill('가짜 자전거')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('5000')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page).toHaveURL(/\/market\/8$/)
  expect(JSON.parse(posts[0].body ?? '{}')).toEqual({ title: '가짜 자전거', content: '설명', tradeType: 'SALE', price: 5000 })
})

// ---- 판매자 본인 상세: 상태 전환 ----

test('판매자 본인의 판매중 글에는 [예약중으로][거래 완료][수정][삭제]가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  await page.goto('/market/1')
  await expect(page.getByRole('button', { name: '예약중으로' })).toBeVisible()
  await expect(page.getByRole('button', { name: '거래 완료' })).toBeVisible()
  await expect(page.getByRole('link', { name: '수정' })).toBeVisible()
  await expect(page.getByRole('button', { name: '삭제' })).toBeVisible()
  await expect(page.getByRole('button', { name: '판매중으로' })).toHaveCount(0)
})

test('[예약중으로]를 누르면 PUT status 본문이 {"status":"RESERVED"}이고 뱃지가 바뀐다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1/status': { status: 200, body: makeItem({ status: 'RESERVED' }) },
  })
  const puts = record(page, 'PUT', '/api/market-items/1/status')
  await page.goto('/market/1')
  await page.getByRole('button', { name: '예약중으로' }).click()
  await expect(page.locator('.badge-RESERVED')).toHaveText('예약중')
  expect(puts).toHaveLength(1)
  expect(JSON.parse(puts[0].body ?? '{}')).toEqual({ status: 'RESERVED' })
  // 예약중이 되면 [판매중으로]가 나오고 [예약중으로]는 사라진다
  await expect(page.getByRole('button', { name: '판매중으로' })).toBeVisible()
  await expect(page.getByRole('button', { name: '예약중으로' })).toHaveCount(0)
})

test('[거래 완료]는 확인 창을 띄우고 취소하면 요청이 나가지 않는다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  const puts = record(page, 'PUT', '/api/market-items/1/status')
  await page.goto('/market/1')
  await page.getByRole('button', { name: '거래 완료' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  // 확인 창 안에 되돌릴 수 없다는 경고 문구가 그대로 보여야 한다
  await expect(dialog.locator('.confirm-message')).toHaveText('거래 완료로 바꾸면 되돌릴 수 없습니다. 완료할까요?')
  await dialog.getByRole('button', { name: '취소' }).click()
  await expect(dialog).toHaveCount(0)
  expect(puts).toHaveLength(0)
  await expect(page.locator('.badge-ON_SALE')).toBeVisible()
})

test('[거래 완료]를 확인하면 DONE이 되고 상태 버튼과 [수정]이 사라지고 [삭제]만 남는다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1/status': { status: 200, body: makeItem({ status: 'DONE' }) },
  })
  const puts = record(page, 'PUT', '/api/market-items/1/status')
  await page.goto('/market/1')
  await page.getByRole('button', { name: '거래 완료' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '거래 완료' }).click()
  await expect(page.locator('.badge-DONE')).toHaveText('거래완료')
  expect(JSON.parse(puts[0].body ?? '{}')).toEqual({ status: 'DONE' })
  // 버튼이 사라져도 결과를 알려 주는 성공 안내가 남는다
  await expect(page.getByRole('status')).toHaveText('거래 완료로 바꿨습니다')
  await expect(page.getByRole('button', { name: '예약중으로' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '판매중으로' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '거래 완료' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '수정' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '삭제' })).toBeVisible()
})

test('상태 변경이 409면 "그 사이 상태가 바뀌었습니다" 문구가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1/status': { status: 409 },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '예약중으로' }).click()
  await expect(page.getByRole('alert')).toHaveText('그 사이 상태가 바뀌었습니다. 새로고침해 주세요.')
})

// ---- 권한별 버튼 ----

test('남의 글을 일반 회원이 보면 버튼이 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem({ sellerId: 5 }) } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('button', { name: '삭제' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '예약중으로' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '수정' })).toHaveCount(0)
})

test('비로그인이 보면 버튼이 없다', async ({ page, api }) => {
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('button', { name: '삭제' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '수정' })).toHaveCount(0)
})

test('관리자가 남의 글을 보면 [삭제]만 보인다', async ({ page, api }) => {
  api.setLoggedIn(true)
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN' } },
    'GET /market-items/1': { status: 200, body: makeItem({ sellerId: 5 }) },
  })
  await page.goto('/market/1')
  await expect(page.getByRole('button', { name: '삭제' })).toBeVisible()
  await expect(page.getByRole('button', { name: '예약중으로' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '거래 완료' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '수정' })).toHaveCount(0)
})

// ---- 수정 ----

test('수정 폼에는 기존 값이 채워져 있고 저장하면 PUT 후 상세로 간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1': { status: 200, body: makeItem({ title: '바뀐 제목' }) },
  })
  const puts = record(page, 'PUT', '/api/market-items/1')
  await page.goto('/market/1/edit')
  await expect(page.getByLabel('제목')).toHaveValue('가짜 자전거')
  await expect(page.getByLabel('본문')).toHaveValue('거의 새 자전거입니다.')
  await expect(page.getByLabel('가격(원)')).toHaveValue('5000')
  await page.getByLabel('제목').fill('바뀐 제목')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page).toHaveURL(/\/market\/1$/)
  expect(JSON.parse(puts[0].body ?? '{}')).toEqual({ title: '바뀐 제목', content: '거의 새 자전거입니다.', tradeType: 'SALE', price: 5000 })
})

test('거래 완료 글의 /edit에는 수정 불가 문구가 보이고 폼은 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem({ status: 'DONE' }) } })
  await page.goto('/market/1/edit')
  await expect(page.getByRole('alert')).toHaveText('거래가 완료된 글은 수정할 수 없습니다.')
  await expect(page.getByRole('button', { name: '저장' })).toHaveCount(0)
})

test('수정 저장이 409면 수정 불가 문구가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1': { status: 409 },
  })
  await page.goto('/market/1/edit')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText('거래가 완료된 글은 수정할 수 없습니다.')
})

// ---- 삭제 ----

test('삭제를 확인하면 DELETE 후 /market으로 간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'DELETE /market-items/1': { status: 204 },
    'GET /market-items': { status: 200, body: [] },
  })
  const dels = record(page, 'DELETE', '/api/market-items/1')
  await page.goto('/market/1')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page).toHaveURL(/\/market$/)
  expect(dels).toHaveLength(1)
})

// ---- 모바일 폭 ----

test('375px 폭에서 목록과 상세에 가로 스크롤이 생기지 않는다', async ({ page, api }) => {
  loginVerified(api)
  const long = makeItem({ title: '아주아주아주아주아주아주아주아주아주아주아주아주긴제목'.repeat(3) })
  api.override({
    'GET /market-items': { status: 200, body: [long, makeItem({ id: 2, free: true, status: 'DONE' })] },
    'GET /market-items/1': { status: 200, body: long },
  })
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/market')
  await expect(page.locator('.row')).toHaveCount(2)
  expect(await hasHorizontalScroll(page)).toBe(false)
  await page.goto('/market/1')
  await expect(page.getByRole('button', { name: '삭제' })).toBeVisible()
  expect(await hasHorizontalScroll(page)).toBe(false)
})

// ================= S13-d review 권장 반영 테스트 =================

// ---- 상태 변경 성공 안내 / 모르는 상태 ----

test('[예약중으로]를 누르면 role=status에 "예약중으로 바꿨습니다"가 보인다', async ({ page, api }) => {
  // 무엇을: 성공 안내 / 왜: 눌린 버튼이 사라져도 스크린리더가 결과를 듣게 하려고
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1/status': { status: 200, body: makeItem({ status: 'RESERVED' }) },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '예약중으로' }).click()
  await expect(page.getByRole('status')).toHaveText('예약중으로 바꿨습니다')
})

test('[판매중으로]를 누르면 "판매중으로 바꿨습니다" 안내가 보인다', async ({ page, api }) => {
  // 무엇을: 예약중 글의 되돌리기 안내 / 왜: 상태마다 문구가 맞게 나오는지 보려고
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem({ status: 'RESERVED' }) },
    'PUT /market-items/1/status': { status: 200, body: makeItem() },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '판매중으로' }).click()
  await expect(page.getByRole('status')).toHaveText('판매중으로 바꿨습니다')
})

test('모르는 status.code(SOLD_OUT)인 글도 상세가 뜨고 상태 버튼은 없다', async ({ page, api }) => {
  // 무엇을: 프론트가 모르는 상태 / 왜: 서버만 먼저 배포돼도 화면이 죽지 않아야 해서
  loginVerified(api)
  const unknown = { ...makeItem(), status: { code: 'SOLD_OUT', name: '품절' } } as unknown as MarketItem
  api.override({ 'GET /market-items/1': { status: 200, body: unknown } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.locator('.badge-SOLD_OUT')).toHaveText('품절')
  await expect(page.getByRole('button', { name: '예약중으로' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '판매중으로' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '거래 완료' })).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

// ---- 상태 변경·삭제 실패 (401·403·404) ----

// 상태 버튼 실패: [응답 상태코드, 화면에 보일 알림 전문]
const statusFailCases: [number, string][] = [
  [403, '상태를 바꾸지 못했습니다. 새로고침 후 다시 시도해 주세요'],
  [404, '이미 삭제된 글입니다'],
]
for (const [code, text] of statusFailCases) {
  test(`상태 변경이 ${code}이면 "${text}" 알림이 보인다`, async ({ page, api }) => {
    // 무엇을: 상태 변경 실패 문구 / 왜: 실패해도 사용자가 다음에 뭘 해야 하는지 알려 주려고
    loginVerified(api)
    api.override({
      'GET /market-items/1': { status: 200, body: makeItem() },
      'PUT /market-items/1/status': { status: code },
    })
    await page.goto('/market/1')
    await page.getByRole('button', { name: '예약중으로' }).click()
    await expect(page.getByRole('alert')).toHaveText(text)
    await expect(page.getByRole('status')).toHaveCount(0) // 실패인데 성공 안내가 남으면 안 된다
  })
}

test('상태 변경이 401(재발급도 실패)이면 다시 로그인 안내가 보인다', async ({ page, api }) => {
  // 무엇을: 로그인 풀림 안내 / 왜: 조용히 실패하면 사용자가 이유를 모르기 때문
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1/status': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '예약중으로' }).click()
  await expect(page.getByRole('alert')).toHaveText(RELOGIN_TEXT)
  await expect(page.getByRole('alert').getByRole('link', { name: '로그인' })).toHaveAttribute('href', '/login')
})

test('삭제가 404면 "이미 삭제된 글입니다"가 보이고 버튼이 다시 살아난다', async ({ page, api }) => {
  // 무엇을: 삭제 실패(이미 지워진 글) / 왜: 실패 뒤에도 화면이 잠긴 채 멈추면 안 돼서
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'DELETE /market-items/1': { status: 404 },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 글입니다')
  await expect(page.getByRole('button', { name: '삭제' })).toBeEnabled()
})

test('관리자가 남의 글을 지우다 403이면 "삭제할 권한이 없습니다"가 보인다', async ({ page, api }) => {
  // 무엇을: 삭제 403 문구(남의 글) / 왜: 본인 글의 403(새로고침 안내)과 문구가 달라야 해서
  api.setLoggedIn(true)
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN' } },
    'GET /market-items/1': { status: 200, body: makeItem({ sellerId: 5 }) },
    'DELETE /market-items/1': { status: 403 },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByRole('alert')).toHaveText('삭제할 권한이 없습니다')
})

test('삭제 확인 창에서 취소하면 DELETE가 나가지 않는다', async ({ page, api }) => {
  // 무엇을: 삭제 취소 / 왜: 실수로 눌러도 글이 지워지면 안 돼서
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem() } })
  const dels = record(page, 'DELETE', '/api/market-items/1')
  await page.goto('/market/1')
  await page.getByRole('button', { name: '삭제' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.locator('.confirm-message')).toHaveText('이 글을 삭제할까요?')
  await dialog.getByRole('button', { name: '취소' }).click()
  await expect(dialog).toHaveCount(0)
  expect(dels).toHaveLength(0)
  await expect(page).toHaveURL(/\/market\/1$/)
})

test('관리자가 남의 거래완료(DONE) 글을 보면 [삭제]만 보인다', async ({ page, api }) => {
  // 무엇을: 관리자 + DONE 조합 / 왜: 상태 버튼·수정이 새어 나오지 않는지 보려고
  api.setLoggedIn(true)
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN' } },
    'GET /market-items/1': { status: 200, body: makeItem({ sellerId: 5, status: 'DONE' }) },
  })
  await page.goto('/market/1')
  await expect(page.getByRole('button', { name: '삭제' })).toBeVisible()
  await expect(page.getByRole('button', { name: '예약중으로' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '판매중으로' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '거래 완료' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '수정' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: '수정' })).toHaveCount(0)
})

// ---- 요청 중 버튼 잠금 ----

test('상태 변경 요청 중에는 버튼이 잠기고 두 번 눌러도 요청은 1번만 나간다', async ({ page, api }) => {
  // 무엇을: 더블클릭 방지 / 왜: 같은 요청이 두 번 나가면 두 번째가 409로 실패하기 때문
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1/status': { status: 200, body: makeItem({ status: 'RESERVED' }) },
  })
  const puts = record(page, 'PUT', '/api/market-items/1/status')
  // 응답을 문이 열릴 때까지 붙잡아 둔다 (나중에 등록한 route가 먼저 실행되고, fallback으로 가짜 응답에 넘긴다)
  let release!: () => void
  const gate = new Promise<void>((r) => (release = r))
  await page.route('**/api/market-items/1/status', async (route) => {
    await gate
    await route.fallback()
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '예약중으로' }).dblclick()
  // 요청 중: 상태 버튼 둘 다 "처리 중..."이고 잠김, 삭제·수정도 잠김
  await expect(page.getByRole('button', { name: '처리 중...' })).toHaveCount(2)
  await expect(page.getByRole('button', { name: '처리 중...' }).first()).toBeDisabled()
  await expect(page.getByRole('button', { name: '처리 중...' }).last()).toBeDisabled()
  await expect(page.getByRole('button', { name: '삭제' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '수정' })).toBeDisabled()
  release()
  await expect(page.getByRole('status')).toHaveText('예약중으로 바꿨습니다')
  expect(puts).toHaveLength(1)
})

test('글 저장 요청 중에는 [저장]이 잠기고 두 번 눌러도 POST는 1번만 나간다', async ({ page, api }) => {
  // 무엇을: 저장 버튼 잠금 / 왜: 같은 글이 두 개 올라가는 것을 막으려고
  loginVerified(api)
  api.override({
    'POST /market-items': { status: 201, body: makeItem({ id: 9 }) },
    'GET /market-items/9': { status: 200, body: makeItem({ id: 9 }) },
  })
  const posts = record(page, 'POST', '/api/market-items')
  let release!: () => void
  const gate = new Promise<void>((r) => (release = r))
  await page.route('**/api/market-items', async (route) => {
    if (route.request().method() !== 'POST') return route.fallback()
    await gate
    await route.fallback()
  })
  await page.goto('/market/new')
  await page.getByLabel('제목').fill('가짜 자전거')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('5000')
  await page.getByRole('button', { name: '저장' }).dblclick()
  await expect(page.getByRole('button', { name: '저장 중...' })).toBeDisabled()
  release()
  await expect(page).toHaveURL(/\/market\/9$/)
  expect(posts).toHaveLength(1)
})

// ---- 글쓰기 화면 접근 ----

test('/market/new에 미인증 회원이 들어오면 학교 인증 안내가 보이고 폼은 없다', async ({ page, api }) => {
  // 무엇을: 미인증 접근 차단 / 왜: 학교 인증 없이는 글을 올릴 수 없어서
  api.setLoggedIn(true)
  await page.goto('/market/new')
  await expect(page.getByRole('status')).toHaveText('중고·나눔 글은 학교 인증 후에 올릴 수 있어요 내 정보에서 학교 인증하기')
  await expect(page.getByRole('link', { name: '내 정보에서 학교 인증하기' })).toHaveAttribute('href', '/me')
  await expect(page.getByRole('button', { name: '저장' })).toHaveCount(0)
  await expect(page.getByLabel('제목')).toHaveCount(0)
})

test('/market/new에 비로그인으로 들어오면 /login으로 간다', async ({ page }) => {
  // 무엇을: 비로그인 접근 / 왜: 로그인 문지기가 동작하는지 보려고
  await page.goto('/market/new')
  await expect(page).toHaveURL(/\/login$/)
})

test('글 저장이 401(재발급도 실패)이면 다시 로그인 안내가 보이고 입력값은 남는다', async ({ page, api }) => {
  // 무엇을: 저장 중 로그인 풀림 / 왜: 쓰던 글이 사라지지 않아야 해서
  loginVerified(api)
  api.override({ 'POST /market-items': { status: 401 }, 'POST /auth/refresh': { status: 401 } })
  await page.goto('/market/new')
  await page.getByLabel('제목').fill('쓰던 제목')
  await page.getByLabel('본문').fill('쓰던 본문')
  await page.getByLabel('가격(원)').fill('5000')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText(RELOGIN_TEXT)
  await expect(page.getByLabel('제목')).toHaveValue('쓰던 제목')
  await expect(page.getByLabel('본문')).toHaveValue('쓰던 본문')
})

// ---- 가격 경계 ----

// 화면에서 막혀야 하는 가격 입력: [설명, 입력값]
const badPrices: [string, string][] = [
  ['10,000,001', '10000001'],
  ['빈 칸', ''],
  ['글자 abc', 'abc'],
]
for (const [label, value] of badPrices) {
  test(`가격이 ${label}이면 화면에서 막히고 POST가 나가지 않는다`, async ({ page, api }) => {
    // 무엇을: 가격 범위 검사 / 왜: 잘못된 값이 서버까지 가지 않게 하려고
    loginVerified(api)
    const posts = record(page, 'POST', '/api/market-items')
    await page.goto('/market/new')
    await page.getByLabel('제목').fill('가격 경계')
    await page.getByLabel('본문').fill('설명')
    await page.getByLabel('가격(원)').fill(value)
    await page.getByRole('button', { name: '저장' }).click()
    await expect(page.getByRole('alert')).toHaveText(PRICE_RANGE_MESSAGE)
    await expect(page).toHaveURL(/\/market\/new$/)
    expect(posts).toHaveLength(0)
  })
}

test('가격이 10,000,000이면 통과해서 POST가 나간다', async ({ page, api }) => {
  // 무엇을: 가격 상한 경계 / 왜: 상한값 자체는 허용돼야 해서
  loginVerified(api)
  api.override({
    'POST /market-items': { status: 201, body: makeItem({ id: 9 }) },
    'GET /market-items/9': { status: 200, body: makeItem({ id: 9 }) },
  })
  const posts = record(page, 'POST', '/api/market-items')
  await page.goto('/market/new')
  await page.getByLabel('제목').fill('가격 경계')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('10000000')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page).toHaveURL(/\/market\/9$/)
  expect(posts).toHaveLength(1)
  expect(JSON.parse(posts[0].body ?? '{}').price).toBe(10000000)
})

// ---- 수정 화면 ----

test('수정 화면에서 남의 글이면 "본인 글만 수정할 수 있습니다"가 보이고 폼은 없다', async ({ page, api }) => {
  // 무엇을: 남의 글 수정 접근 / 왜: 주소를 직접 쳐도 폼이 열리면 안 돼서
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: makeItem({ sellerId: 5 }) } })
  await page.goto('/market/1/edit')
  await expect(page.getByRole('alert')).toHaveText('본인 글만 수정할 수 있습니다')
  await expect(page.getByRole('button', { name: '저장' })).toHaveCount(0)
  await expect(page.getByLabel('제목')).toHaveCount(0)
})

test('나눔 글 수정에는 가격 칸이 없고 PUT 본문에 price 키가 없다', async ({ page, api }) => {
  // 무엇을: 나눔 글 수정 / 왜: 나눔은 가격을 보내지 않는 규칙(서버가 0으로 저장)이라서
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem({ free: true }) },
    'PUT /market-items/1': { status: 200, body: makeItem({ free: true }) },
  })
  const puts = record(page, 'PUT', '/api/market-items/1')
  await page.goto('/market/1/edit')
  await expect(page.getByLabel('제목')).toHaveValue('가짜 자전거')
  await expect(page.getByLabel('가격(원)')).toHaveCount(0)
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page).toHaveURL(/\/market\/1$/)
  const body = JSON.parse(puts[0].body ?? '{}')
  expect('price' in body).toBe(false)
  expect(body.tradeType).toBe('FREE')
})

test('판매 글을 나눔으로 바꿔 저장하면 PUT 본문에 price가 없고 tradeType이 FREE다', async ({ page, api }) => {
  // 무엇을: 판매→나눔 전환 / 왜: 폼에 남은 옛 가격(5000)이 새어 나가면 안 돼서
  loginVerified(api)
  api.override({
    'GET /market-items/1': { status: 200, body: makeItem() },
    'PUT /market-items/1': { status: 200, body: makeItem({ free: true }) },
  })
  const puts = record(page, 'PUT', '/api/market-items/1')
  await page.goto('/market/1/edit')
  await expect(page.getByLabel('가격(원)')).toHaveValue('5000')
  await page.getByLabel('판매/나눔').selectOption('FREE')
  await expect(page.getByLabel('가격(원)')).toHaveCount(0)
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page).toHaveURL(/\/market\/1$/)
  const body = JSON.parse(puts[0].body ?? '{}')
  expect('price' in body).toBe(false)
  expect(body.tradeType).toBe('FREE')
})

// ---- 목록 필터 오류 ----

test('?schoolId=abc에서 400이 오면 "잘못된 조건입니다" 문구가 보인다', async ({ page, api }) => {
  // 무엇을: 주소 값이 이상할 때 / 왜: 빈 목록이 아니라 이유를 알려 줘야 해서
  api.override({ 'GET /market-items': { status: 400 } })
  await page.goto('/market?schoolId=abc')
  await expect(page.getByRole('alert')).toHaveText('잘못된 조건입니다. 학교·판매/나눔·상태를 다시 골라 주세요')
  await expect(page.getByLabel('학교', { exact: true })).toHaveValue('abc')
})

test('?schoolId=999에서 404가 오면 "존재하지 않는 학교입니다" 문구가 보인다', async ({ page, api }) => {
  // 무엇을: 없는 학교 필터 / 왜: 없는 학교와 글이 없는 것을 구분해 보여 주려고
  api.override({ 'GET /market-items': { status: 404 } })
  await page.goto('/market?schoolId=999')
  await expect(page.getByRole('alert')).toHaveText('존재하지 않는 학교입니다')
})

test('?status=xxx이면 상태 드롭다운에 "알 수 없는 상태 (xxx)" 옵션이 끼어 선택돼 있다', async ({ page, api }) => {
  // 무엇을: 모르는 쿼리 값 표시 / 왜: "전체 상태"로 거짓 표시되면 빠져나올 수 없어서
  api.override({ 'GET /market-items': { status: 400 } })
  await page.goto('/market?status=xxx')
  await expect(page.getByLabel('상태')).toHaveValue('xxx')
  await expect(page.getByRole('option', { name: '알 수 없는 상태 (xxx)' })).toHaveCount(1)
  // 전체 상태를 고르면 주소에서 status가 빠진다
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await page.getByLabel('상태').selectOption('')
  await expect(page).not.toHaveURL(/status=/)
  await expect(page.getByLabel('상태')).toHaveValue('')
})
