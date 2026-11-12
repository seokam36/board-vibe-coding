import { test, expect, fakeMarketListItem, loginVerified } from './mockApi.ts'
import type { Page } from '@playwright/test'
import type { MarketItem, MarketItemPhoto, MarketListItem } from '../src/types.ts'

// S13-g5-1: 중고 글 목록의 대표 사진 썸네일, 상세의 사진 보기. 서버 응답·사진 파일은 mockApi 가짜 응답이다
// (사진 파일 주소는 mockApi가 기본으로 1x1 PNG를 준다. 실패 장면만 override로 404를 준다)

// 가짜 사진 하나. 큰 사진 1365x2048, 썸네일 213x320
function photo(id: number): MarketItemPhoto {
  return { id, width: 1365, height: 2048, thumbWidth: 213, thumbHeight: 320, contentType: 'image/jpeg' }
}

// 상세용 가짜 글. 사진 id 목록을 넘기면 그 순서로 photos가 채워진다
function detailItem(photoIds: number[]): MarketItem {
  const { coverPhoto, ...rest } = fakeMarketListItem
  void coverPhoto // 목록 전용 칸은 상세에 없으므로 뺀다
  return { ...rest, photos: photoIds.map(photo) }
}

// 목록 항목: 사진 있음(id 7) / 없음
const withCover: MarketListItem = { ...fakeMarketListItem, id: 1, title: '사진 있는 글' }
const withoutCover: MarketListItem = { ...fakeMarketListItem, id: 2, title: '사진 없는 글', coverPhoto: null }

// 가로 스크롤이 생기는지: 문서 가로 폭이 화면 폭을 넘는지 본다
async function hasHorizontalScroll(page: Page) {
  return page.evaluate<boolean>('document.documentElement.scrollWidth > document.documentElement.clientWidth')
}

// 큰 사진: alt가 "사진 n / 전체" 모양인 img
const mainPhoto = (page: Page) => page.getByRole('img', { name: /^사진 \d+ \/ \d+$/ })

// ---- 목록 ----

test('목록에서 대표 사진이 있는 글은 썸네일 img(alt, ?size=thumb)가 보이고 없는 글은 img가 없다', async ({ page, api }) => {
  // 무엇을: 대표 사진 유무에 따른 썸네일 표시 / 왜: 사진 있는 글만 그림이 나오고 없는 글은 빈칸이어야 해서
  api.override({ 'GET /market-items': { status: 200, body: [withCover, withoutCover] } })
  await page.goto('/market')
  const rows = page.locator('.row')
  await expect(rows).toHaveCount(2)
  const img = rows.nth(0).getByRole('img', { name: '사진 있는 글 대표 사진' })
  await expect(img).toBeVisible()
  await expect(img).toHaveAttribute('src', '/api/market-items/1/photos/7?size=thumb')
  // 사진 없는 글 줄에는 img가 없다
  await expect(rows.nth(1).locator('img')).toHaveCount(0)
  await expect(rows.nth(1)).toContainText('사진 없는 글')
})

test('목록에서 사진 요청이 404로 실패하면 img가 사라지고 빈칸이 남는다', async ({ page, api }) => {
  // 무엇을: 사진 불러오기 실패 / 왜: 깨진 그림 아이콘 대신 빈칸으로 바뀌어야 해서
  api.override({
    'GET /market-items': { status: 200, body: [withCover] },
    'GET /market-items/1/photos/7': { status: 404 },
  })
  await page.goto('/market')
  const row = page.locator('.row')
  await expect(row).toHaveCount(1)
  await expect(row.locator('img')).toHaveCount(0)
  await expect(row.locator('.market-thumb-empty')).toHaveCount(1)
  await expect(row.locator('.market-thumb-empty')).toHaveAttribute('aria-hidden', 'true')
})

// ---- 상세 ----

test('상세: 사진이 0장이면 큰 사진도 썸네일 버튼도 없다', async ({ page, api }) => {
  // 무엇을: 사진 영역 숨김 / 왜: 사진 없는 글에 빈 영역이 생기면 안 돼서
  api.override({ 'GET /market-items/1': { status: 200, body: detailItem([]) } })
  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.locator('.market-photos')).toHaveCount(0)
  await expect(mainPhoto(page)).toHaveCount(0)
  await expect(page.getByRole('button', { name: /^사진 \d+ 보기$/ })).toHaveCount(0)
})

test('상세: 사진이 1장이면 큰 사진만 보이고 썸네일 버튼은 없다', async ({ page, api }) => {
  // 무엇을: 1장일 때 / 왜: 고를 사진이 없는데 버튼이 있으면 헷갈려서
  api.override({ 'GET /market-items/1': { status: 200, body: detailItem([7]) } })
  await page.goto('/market/1')
  const main = mainPhoto(page)
  await expect(main).toHaveCount(1)
  await expect(main).toHaveAttribute('alt', '사진 1 / 1')
  await expect(main).toHaveAttribute('src', '/api/market-items/1/photos/7')
  await expect(page.getByRole('button', { name: /^사진 \d+ 보기$/ })).toHaveCount(0)
})

test('상세: 사진이 3장이면 버튼 3개, 처음엔 1번이 눌린 상태이고 3번을 누르면 큰 사진이 3번으로 바뀐다', async ({ page, api }) => {
  // 무엇을: 썸네일로 큰 사진 바꾸기 / 왜: 여러 장을 차례로 볼 수 있어야 해서
  api.override({ 'GET /market-items/1': { status: 200, body: detailItem([7, 8, 9]) } })
  await page.goto('/market/1')
  const buttons = page.getByRole('button', { name: /^사진 \d+ 보기$/ })
  await expect(buttons).toHaveCount(3)
  await expect(page.getByRole('button', { name: '사진 1 보기' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('button', { name: '사진 2 보기' })).toHaveAttribute('aria-pressed', 'false')
  await expect(page.getByRole('button', { name: '사진 3 보기' })).toHaveAttribute('aria-pressed', 'false')
  await expect(mainPhoto(page)).toHaveAttribute('alt', '사진 1 / 3')
  await expect(mainPhoto(page)).toHaveAttribute('src', '/api/market-items/1/photos/7')

  await page.getByRole('button', { name: '사진 3 보기' }).click()

  await expect(mainPhoto(page)).toHaveAttribute('alt', '사진 3 / 3')
  // 큰 사진 주소는 그 사진의 id이고 썸네일 쿼리가 없다
  await expect(mainPhoto(page)).toHaveAttribute('src', '/api/market-items/1/photos/9')
  await expect(page.getByRole('button', { name: '사진 3 보기' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByRole('button', { name: '사진 1 보기' })).toHaveAttribute('aria-pressed', 'false')
})

test('상세: 3번 사진을 보다가 상태 변경 응답의 사진이 1장으로 줄면 "사진 1 / 1"로 맞춰지고 썸네일 줄이 사라진다', async ({ page, api }) => {
  // 무엇을: 고른 번호가 사진 수를 넘을 때 인덱스 맞추기 / 왜: 없는 사진을 가리키며 화면이 비거나 깨지면 안 돼서
  loginVerified(api) // 판매자 본인(id 1) 로그인. 상태 버튼은 본인에게만 보인다
  const 줄어든_글: MarketItem = { ...detailItem([7]), status: { code: 'RESERVED', name: '예약중' } }
  api.override({
    'GET /market-items/1': { status: 200, body: detailItem([7, 8, 9]) },
    'PUT /market-items/1/status': { status: 200, body: 줄어든_글 },
  })
  await page.goto('/market/1')
  await page.getByRole('button', { name: '사진 3 보기' }).click()
  await expect(mainPhoto(page)).toHaveAttribute('alt', '사진 3 / 3')

  await page.getByRole('button', { name: '예약중으로' }).click()

  await expect(page.getByText('예약중으로 바꿨습니다')).toBeVisible()
  await expect(mainPhoto(page)).toHaveAttribute('alt', '사진 1 / 1')
  await expect(mainPhoto(page)).toHaveAttribute('src', '/api/market-items/1/photos/7')
  await expect(page.getByRole('button', { name: /^사진 \d+ 보기$/ })).toHaveCount(0)
})

test('상세: 큰 사진 요청이 404면 안내 글이 보이고 img가 없으며, 다른 썸네일을 누르면 다시 img가 보인다', async ({ page, api }) => {
  // 무엇을: 큰 사진 실패 안내와 다른 사진으로 재시도 / 왜: 한 장이 깨져도 다른 사진은 볼 수 있어야 해서
  api.override({
    'GET /market-items/1': { status: 200, body: detailItem([7, 8, 9]) },
    'GET /market-items/1/photos/7': { status: 404 }, // 1번 큰 사진만 실패 (8·9는 기본 200)
  })
  await page.goto('/market/1')
  const frame = page.locator('.market-photo-frame')
  await expect(frame.getByText('사진을 불러오지 못했습니다')).toBeVisible()
  await expect(mainPhoto(page)).toHaveCount(0)
  await expect(frame.locator('img')).toHaveCount(0)
  // 안내 글은 같은 틀(.market-photo-frame) 안에 있는 일반 텍스트다(role 없음)
  await expect(frame.locator('[role]')).toHaveCount(0)
  // 썸네일 버튼은 그대로 남아 있다
  await expect(page.getByRole('button', { name: /^사진 \d+ 보기$/ })).toHaveCount(3)

  await page.getByRole('button', { name: '사진 2 보기' }).click()

  await expect(mainPhoto(page)).toHaveAttribute('alt', '사진 2 / 3')
  await expect(mainPhoto(page)).toHaveAttribute('src', '/api/market-items/1/photos/8')
  await expect(frame.getByText('사진을 불러오지 못했습니다')).toHaveCount(0)
})

test('상세: 키보드(Tab으로 썸네일 버튼에 가서 Enter)로 큰 사진을 바꿀 수 있다', async ({ page, api }) => {
  // 무엇을: 키보드 조작 / 왜: 마우스 없이도 사진을 고를 수 있어야 해서
  api.override({ 'GET /market-items/1': { status: 200, body: detailItem([7, 8, 9]) } })
  await page.goto('/market/1')
  await expect(mainPhoto(page)).toHaveAttribute('alt', '사진 1 / 3')
  const 둘째 = page.getByRole('button', { name: '사진 2 보기' })

  // 첫 썸네일 버튼으로 포커스를 옮긴 뒤(Tab) 다음 버튼으로 한 번 더 Tab
  await page.getByRole('button', { name: '사진 1 보기' }).focus()
  await page.keyboard.press('Tab')
  await expect(둘째).toBeFocused()
  await page.keyboard.press('Enter')

  await expect(mainPhoto(page)).toHaveAttribute('alt', '사진 2 / 3')
  await expect(mainPhoto(page)).toHaveAttribute('src', '/api/market-items/1/photos/8')
  await expect(둘째).toHaveAttribute('aria-pressed', 'true')
})

// ---- 모바일 폭 ----

test('375px 폭에서 사진이 있는 목록과 상세(3장)에 가로 스크롤이 생기지 않는다', async ({ page, api }) => {
  // 무엇을: 모바일 폭 가로 스크롤 / 왜: 큰 사진(1365px 폭)이 화면 밖으로 삐져나오면 안 돼서
  api.override({
    'GET /market-items': { status: 200, body: [withCover, withoutCover] },
    'GET /market-items/1': { status: 200, body: detailItem([7, 8, 9]) },
  })
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/market')
  await expect(page.locator('.row')).toHaveCount(2)
  await expect(page.getByRole('img', { name: '사진 있는 글 대표 사진' })).toBeVisible()
  expect(await hasHorizontalScroll(page)).toBe(false)
  await page.goto('/market/1')
  await expect(page.getByRole('button', { name: '사진 3 보기' })).toBeVisible()
  await expect(mainPhoto(page)).toBeVisible()
  expect(await hasHorizontalScroll(page)).toBe(false)
})
