import { test, expect } from './mockApi.ts'
import type { Page } from '@playwright/test'
import type { MarketItem } from '../src/types.ts'

// S13-f: 중고·나눔 목록 제목 검색 화면. 서버 응답은 mockApi 가짜 응답이다

const school = { id: 1, name: '가짜대학교' }
const BAD_Q_MESSAGE = '잘못된 조건입니다. 학교·판매/나눔·상태를 다시 고르고 검색어는 50자 이하로 써 주세요'

function makeItem(id = 1, title = '가짜 자전거'): MarketItem {
  return {
    id,
    title,
    content: '내용',
    tradeType: { code: 'SALE', name: '판매' },
    price: 5000,
    status: { code: 'ON_SALE', name: '판매중' },
    school,
    seller: { id: 1, nickname: '테스터' },
    createdAt: '2026-01-02T00:00:00Z',
    photos: [],
  }
}

// GET /api/market-items 요청을 모은다
function recordList(page: Page) {
  const calls: URL[] = []
  page.on('request', (r) => {
    const u = new URL(r.url())
    if (r.method() === 'GET' && u.pathname === '/api/market-items') calls.push(u)
  })
  return calls
}

// 가장 최근 요청의 쿼리 값
const lastParam = (calls: URL[], name: string) => calls[calls.length - 1]?.searchParams.get(name)

test('검색어를 입력하고 Enter를 누르면 q가 요청과 주소에 들어가고 기존 필터는 유지된다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  const calls = recordList(page)
  await page.goto('/market?schoolId=1&tradeType=SALE')
  await expect(page.locator('.row')).toHaveCount(1)
  await page.getByLabel('제목 검색어').fill('자전거')
  await page.getByLabel('제목 검색어').press('Enter')
  await expect(page).toHaveURL(/q=%EC%9E%90%EC%A0%84%EA%B1%B0|q=자전거/)
  const url = new URL(page.url())
  expect(url.searchParams.get('q')).toBe('자전거')
  expect(url.searchParams.get('schoolId')).toBe('1')
  expect(url.searchParams.get('tradeType')).toBe('SALE')
  await expect.poll(() => lastParam(calls, 'q')).toBe('자전거')
  expect(lastParam(calls, 'schoolId')).toBe('1')
  expect(lastParam(calls, 'tradeType')).toBe('SALE')
})

test('[검색] 버튼을 클릭해도 q가 요청과 주소에 들어가고 기존 필터는 유지된다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  const calls = recordList(page)
  await page.goto('/market?status=ON_SALE')
  await expect(page.locator('.row')).toHaveCount(1)
  await page.getByLabel('제목 검색어').fill('자전거')
  await page.getByRole('button', { name: '검색' }).click()
  await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('자전거')
  expect(new URL(page.url()).searchParams.get('status')).toBe('ON_SALE')
  await expect.poll(() => lastParam(calls, 'q')).toBe('자전거')
  expect(lastParam(calls, 'status')).toBe('ON_SALE')
})

test('빈칸으로 제출하면 주소에서 q가 빠지고 요청에도 q가 없다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  const calls = recordList(page)
  await page.goto('/market?q=자전거&status=ON_SALE')
  await expect(page.getByLabel('제목 검색어')).toHaveValue('자전거')
  await page.getByLabel('제목 검색어').fill('   ')
  await page.getByRole('button', { name: '검색' }).click()
  await expect.poll(() => new URL(page.url()).searchParams.has('q')).toBe(false)
  expect(new URL(page.url()).searchParams.get('status')).toBe('ON_SALE')
  await expect.poll(() => calls[calls.length - 1]?.searchParams.has('q')).toBe(false)
})

test('?q=자전거로 바로 열면 입력칸에 값이 있고 요청에 q가 담긴다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  const calls = recordList(page)
  await page.goto('/market?q=자전거')
  await expect(page.getByLabel('제목 검색어')).toHaveValue('자전거')
  await expect.poll(() => lastParam(calls, 'q')).toBe('자전거')
  // 새로고침해도 같다
  await page.reload()
  await expect(page.getByLabel('제목 검색어')).toHaveValue('자전거')
  await expect.poll(() => lastParam(calls, 'q')).toBe('자전거')
})

test('검색 결과가 0건이면 "검색 결과가 없습니다"가 보이고 "아직 올라온 글이 없습니다"는 안 보인다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await page.goto('/market?q=없는물건')
  await expect(page.getByText('검색 결과가 없습니다', { exact: true })).toHaveText('검색 결과가 없습니다')
  await expect(page.getByText('아직 올라온 글이 없습니다')).toHaveCount(0)
})

test('q가 없고 0건이면 기존 "아직 올라온 글이 없습니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await page.goto('/market')
  await expect(page.getByText('아직 올라온 글이 없습니다', { exact: true })).toBeVisible()
  await expect(page.getByText('검색 결과가 없습니다')).toHaveCount(0)
})

test('q가 있을 때 400이면 검색어 50자 안내 문구가 보인다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 400 } })
  await page.goto('/market?q=자전거')
  await expect(page.getByRole('alert')).toHaveText(BAD_Q_MESSAGE)
})

test('Enter로 검색한 뒤에도 입력칸이 포커스를 유지한다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  await page.goto('/market')
  const input = page.getByLabel('제목 검색어')
  await input.fill('자전거')
  await input.press('Enter')
  await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('자전거')
  await expect(input).toHaveValue('자전거')
  await expect(input).toBeFocused()
  // 이어서 바로 타이핑해도 입력칸에 들어간다
  await page.keyboard.type('앞')
  await expect(input).toHaveValue('자전거앞')
})

test('[검색] 버튼을 클릭한 뒤 입력칸 값이 주소 q와 맞다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  await page.goto('/market')
  const input = page.getByLabel('제목 검색어')
  await input.fill('자전거')
  await page.getByRole('button', { name: '검색' }).click()
  await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('자전거')
  await expect(input).toHaveValue('자전거')
})

test('뒤로 가기를 하면 입력칸이 주소의 q와 맞는다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  await page.goto('/market?q=자전거')
  await expect(page.getByLabel('제목 검색어')).toHaveValue('자전거')
  await page.getByLabel('제목 검색어').fill('노트북')
  await page.getByRole('button', { name: '검색' }).click()
  await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('노트북')
  await expect(page.getByLabel('제목 검색어')).toHaveValue('노트북')
  await page.goBack()
  await expect.poll(() => new URL(page.url()).searchParams.get('q')).toBe('자전거')
  await expect(page.getByLabel('제목 검색어')).toHaveValue('자전거')
})

test('필터 select를 바꿔도 q가 주소와 요청에 유지된다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [makeItem()] } })
  const calls = recordList(page)
  await page.goto('/market?q=자전거')
  await expect(page.locator('.row')).toHaveCount(1)
  await page.getByLabel('판매/나눔').selectOption('FREE')
  await expect.poll(() => new URL(page.url()).searchParams.get('tradeType')).toBe('FREE')
  expect(new URL(page.url()).searchParams.get('q')).toBe('자전거')
  await expect.poll(() => lastParam(calls, 'tradeType')).toBe('FREE')
  expect(lastParam(calls, 'q')).toBe('자전거')
})

test('검색 입력칸은 role=search 안에 있고 최대 50자까지만 입력된다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await page.goto('/market')
  const input = page.getByRole('search').getByLabel('제목 검색어')
  await expect(input).toHaveAttribute('maxlength', '50')
  await input.fill('가'.repeat(50))
  await expect(input).toHaveValue('가'.repeat(50))
})
