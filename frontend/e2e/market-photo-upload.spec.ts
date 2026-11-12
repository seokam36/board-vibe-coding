import { test, expect, fakeMarketListItem, fakeUser, loginVerified } from './mockApi.ts'
import type { Page } from '@playwright/test'
import type { MarketItem, MarketItemPhoto } from '../src/types.ts'

// S13-g5-2: 글쓰기·수정 화면에서 사진 추가·삭제. 서버 응답은 mockApi 가짜 응답이다

const RELOGIN_TEXT = '다시 로그인해 주세요 로그인' // 안내 문구 + 로그인 링크 글자

// 가짜 사진 하나
function photo(id: number): MarketItemPhoto {
  return { id, width: 1365, height: 2048, thumbWidth: 213, thumbHeight: 320, contentType: 'image/jpeg' }
}

// 수정 화면용 가짜 글. 사진 id 목록 순서대로 photos가 채워진다 (판매자는 로그인한 나, id 1)
function detailItem(photoIds: number[]): MarketItem {
  const { coverPhoto, ...rest } = fakeMarketListItem
  void coverPhoto // 목록 전용 칸은 상세에 없으므로 뺀다
  return { ...rest, photos: photoIds.map(photo) }
}

// 올릴 가짜 파일. 내용은 중요하지 않고 이름·종류·크기만 본다
function jpg(name: string) {
  return { name, mimeType: 'image/jpeg', buffer: Buffer.from('fake-jpeg-' + name) }
}

// 수정 화면을 열 준비: 로그인 + 사진 n장짜리 글
function setupEdit(api: Parameters<typeof loginVerified>[0], photoIds: number[]) {
  loginVerified(api)
  api.override({ 'GET /market-items/1': { status: 200, body: detailItem(photoIds) } })
}

// 글쓰기 화면을 열 준비: 로그인 + 글 만들기(id 7)·상세 응답
function setupCreate(api: Parameters<typeof loginVerified>[0]) {
  loginVerified(api)
  api.override({
    'POST /market-items': { status: 201, body: { ...detailItem([]), id: 7 } },
    'GET /market-items/7': { status: 200, body: { ...detailItem([]), id: 7 } },
  })
}

// 글쓰기 폼을 채우고 [저장]을 누른다
async function fillAndSave(page: Page) {
  await page.getByLabel('제목').fill('새 자전거')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('5000')
  await page.getByRole('button', { name: '저장' }).click()
}

// 사진 id n개 (1부터)
const ids = (n: number) => Array.from({ length: n }, (_, i) => i + 1)

// 사진 영역 안의 글자 버튼/입력
const fileInput = (page: Page) => page.getByLabel('사진 파일 선택')
const title = (page: Page) => page.getByRole('heading', { name: /^사진 \(\d+\/10\)$/ })

// 지정한 메서드·경로로 나간 요청을 모은다
function record(page: Page, method: string, pathname: string) {
  const calls: { url: string; body: string | null }[] = []
  page.on('request', (r) => {
    if (r.method() === method && new URL(r.url()).pathname === pathname) calls.push({ url: r.url(), body: r.postData() })
  })
  return calls
}

// 사진 올리기(POST .../photos)를 직접 맡는다. mockApi 기본 응답보다 나중에 등록해서 먼저 적용된다.
// statuses: 호출 순서별 상태코드(없으면 201), delayMs: 응답 전에 기다리는 시간, log: 시작·끝 순서 기록
function takeUploads(page: Page, opts: { statuses?: number[]; delayMs?: number; log?: string[] } = {}) {
  let n = 0
  return page.route(
    (url) => /^\/api\/market-items\/[^/]+\/photos$/.test(url.pathname),
    async (route) => {
      if (route.request().method() !== 'POST') return route.fallback()
      const idx = n++
      opts.log?.push(`start ${idx + 1}`)
      if (opts.delayMs) await new Promise((r) => setTimeout(r, opts.delayMs))
      opts.log?.push(`end ${idx + 1}`)
      const status = opts.statuses?.[idx] ?? 201
      if (status === 201) await route.fulfill({ status, json: photo(200 + idx) })
      else await route.fulfill({ status, json: { message: 'x' } })
    },
  )
}

// 가로 스크롤이 생기는지: 문서 가로 폭이 화면 폭을 넘는지 본다
async function hasHorizontalScroll(page: Page) {
  return page.evaluate<boolean>('document.documentElement.scrollWidth > document.documentElement.clientWidth')
}

// ---- 수정 화면 ----

test('수정: 기존 사진 2장에 2장을 고르면 요청이 차례로 가고 파트 이름이 file이며 "사진 (4/10)"이 된다', async ({ page, api }) => {
  // 무엇을: 여러 장 차례 올리기 / 왜: 서버가 이미지 처리를 2개씩만 해서 동시에 보내면 안 돼서
  setupEdit(api, [1, 2])
  const log: string[] = []
  await takeUploads(page, { delayMs: 150, log })
  const posts = record(page, 'POST', '/api/market-items/1/photos')
  await page.goto('/market/1/edit')
  await expect(title(page)).toHaveText('사진 (2/10)')

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await expect(title(page)).toHaveText('사진 (4/10)')
  expect(log).toEqual(['start 1', 'end 1', 'start 2', 'end 2']) // 두 번째는 첫 응답 뒤에 시작
  expect(posts).toHaveLength(2)
  expect(posts[0].body).toContain('name="file"')
  expect(posts[0].body).toContain('filename="a.jpg"')
  expect(posts[1].body).toContain('name="file"')
  expect(posts[1].body).toContain('filename="b.jpg"')
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('수정: 삭제를 확인하면 DELETE 주소가 맞고 목록에서 빠지며, 취소하면 요청이 없다', async ({ page, api }) => {
  // 무엇을: 사진 삭제 확인 창 / 왜: 실수로 누른 삭제가 바로 지워지면 안 돼서
  setupEdit(api, [1, 2])
  const deletes = record(page, 'DELETE', '/api/market-items/1/photos/2')
  await page.goto('/market/1/edit')
  await expect(title(page)).toHaveText('사진 (2/10)')

  const dialog = page.getByRole('dialog')
  await page.getByRole('button', { name: '사진 2 삭제' }).click() // 취소
  await expect(dialog).toContainText('사진 2번을 삭제할까요?')
  await dialog.getByRole('button', { name: '취소' }).click()
  await expect(dialog).toBeHidden()
  expect(deletes).toHaveLength(0)
  await expect(title(page)).toHaveText('사진 (2/10)')

  await page.getByRole('button', { name: '사진 2 삭제' }).click() // 수락
  await dialog.getByRole('button', { name: '삭제' }).click()
  await expect(title(page)).toHaveText('사진 (1/10)')
  expect(deletes).toHaveLength(1)
  await expect(page.getByRole('button', { name: '사진 2 삭제' })).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

for (const c of [
  { status: 400, text: 'JPEG·PNG 사진만 올릴 수 있습니다 (손상되었거나 너무 큰 사진일 수 있습니다)' },
  { status: 413, text: '사진 한 장은 10MB 이하여야 합니다' },
  { status: 409, text: '사진은 10장까지 올릴 수 있고, 거래가 완료된 글에는 올릴 수 없습니다' },
  { status: 503, text: '서버가 바쁩니다. 잠시 뒤 다시 시도해 주세요' },
]) {
  test(`수정: 사진 올리기가 ${c.status}면 알맞은 문구가 뜨고 목록은 그대로다`, async ({ page, api }) => {
    // 무엇을: 서버 에러별 문구 / 왜: 이유를 알려 줘야 다시 시도할 수 있어서
    setupEdit(api, [1])
    await takeUploads(page, { statuses: [c.status] })
    await page.goto('/market/1/edit')

    await fileInput(page).setInputFiles([jpg('a.jpg')])

    await expect(page.getByRole('alert')).toHaveText(c.text)
    await expect(title(page)).toHaveText('사진 (1/10)')
  })
}

test('수정: 사진 올리기가 401이면 로그인 안내가 뜬다', async ({ page, api }) => {
  // 무엇을: 로그인 풀림 안내 / 왜: 다른 에러 문구로 오해하지 않게
  setupEdit(api, [1])
  api.override({ 'POST /auth/refresh': { status: 401 } })
  await takeUploads(page, { statuses: [401] })
  await page.goto('/market/1/edit')

  await fileInput(page).setInputFiles([jpg('a.jpg')])

  await expect(page.getByRole('alert')).toHaveText(RELOGIN_TEXT)
})

test('수정: 2장 중 1장이 413이면 성공한 1장은 붙고 문구 뒤에 장수 접미사는 붙지 않는다', async ({ page, api }) => {
  // 무엇을: 일부 실패 처리 / 왜: 413은 그 사진만 문제라 나머지는 계속 올려야 해서
  setupEdit(api, [1])
  await takeUploads(page, { statuses: [413, 201] })
  const posts = record(page, 'POST', '/api/market-items/1/photos')
  await page.goto('/market/1/edit')

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await expect(page.getByRole('alert')).toHaveText('사진 한 장은 10MB 이하여야 합니다')
  await expect(title(page)).toHaveText('사진 (2/10)')
  expect(posts).toHaveLength(2)
})

test('수정: 2장을 올리는데 모두 413이면 "(2장 실패)"가 붙는다', async ({ page, api }) => {
  // 무엇을: 실패 장수 표시 / 왜: 몇 장이 안 올라갔는지 알아야 해서
  setupEdit(api, [1])
  await takeUploads(page, { statuses: [413, 413] })
  await page.goto('/market/1/edit')

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await expect(page.getByRole('alert')).toHaveText('사진 한 장은 10MB 이하여야 합니다 (2장 실패)')
  await expect(title(page)).toHaveText('사진 (1/10)')
})

test('수정: 2장 중 첫 장이 401이면 두 번째 요청은 가지 않는다', async ({ page, api }) => {
  // 무엇을: 401이면 중단 / 왜: 다음 장도 똑같이 실패할 요청을 보내지 않으려고
  setupEdit(api, [1])
  api.override({ 'POST /auth/refresh': { status: 401 } })
  const log: string[] = []
  await takeUploads(page, { statuses: [401, 201], log })
  await page.goto('/market/1/edit')

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await expect(page.getByRole('alert')).toHaveText(RELOGIN_TEXT)
  expect(log.filter((l) => l.startsWith('start'))).not.toContain('start 2')
  await expect(title(page)).toHaveText('사진 (1/10)')
})

test('수정: 지우기가 404면 "이미 삭제된 사진입니다"가 뜨고 목록에서도 빠진다', async ({ page, api }) => {
  // 무엇을: 이미 지워진 사진 / 왜: 화면에 남아 있으면 계속 눌러 같은 에러가 나서
  setupEdit(api, [1, 2])
  api.override({ 'DELETE /market-items/1/photos/2': { status: 404 } })
  await page.goto('/market/1/edit')

  await page.getByRole('button', { name: '사진 2 삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 사진입니다')
  await expect(title(page)).toHaveText('사진 (1/10)')
})

test('수정: 지우기가 409면 "거래가 완료된 글의 사진은 지울 수 없습니다"가 뜨고 목록은 그대로다', async ({ page, api }) => {
  // 무엇을: 거래완료 글 사진 삭제 거절 / 왜: 지워진 줄 알면 안 돼서
  setupEdit(api, [1, 2])
  api.override({ 'DELETE /market-items/1/photos/2': { status: 409 } })
  await page.goto('/market/1/edit')

  await page.getByRole('button', { name: '사진 2 삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('거래가 완료된 글의 사진은 지울 수 없습니다')
  await expect(title(page)).toHaveText('사진 (2/10)')
})

test('수정: 사전 검사로 gif는 서버 요청 없이 안내 문구가 뜬다', async ({ page, api }) => {
  // 무엇을: 종류 검사 / 왜: 어차피 거절될 파일을 보내지 않으려고
  setupEdit(api, [1])
  const posts = record(page, 'POST', '/api/market-items/1/photos')
  await page.goto('/market/1/edit')

  await fileInput(page).setInputFiles([{ name: 'a.gif', mimeType: 'image/gif', buffer: Buffer.from('gif') }])

  await expect(page.getByRole('alert')).toHaveText('JPEG·PNG 사진만 올릴 수 있습니다. 1장은 받지 않았습니다.')
  expect(posts).toHaveLength(0)
  await expect(title(page)).toHaveText('사진 (1/10)')
})

test('수정: 사전 검사로 10MB 넘는 파일은 서버 요청 없이 안내 문구가 뜬다', async ({ page, api }) => {
  // 무엇을: 크기 검사 / 왜: 큰 파일을 보내느라 기다리지 않게
  setupEdit(api, [1])
  const posts = record(page, 'POST', '/api/market-items/1/photos')
  await page.goto('/market/1/edit')

  await fileInput(page).setInputFiles([{ name: 'big.jpg', mimeType: 'image/jpeg', buffer: Buffer.alloc(11 * 1024 * 1024) }])

  await expect(page.getByRole('alert')).toHaveText('사진 한 장은 10MB 이하여야 합니다. 1장은 받지 않았습니다.')
  expect(posts).toHaveLength(0)
})

test('수정: 사전 검사로 10장을 넘기는 선택은 서버 요청 없이 안내 문구가 뜬다', async ({ page, api }) => {
  // 무엇을: 장수 검사 / 왜: 11장째는 서버가 거절해서
  setupEdit(api, [1])
  const posts = record(page, 'POST', '/api/market-items/1/photos')
  await page.goto('/market/1/edit')

  // 이미 1장 + 10장 = 11장 중 10장 한도 -> 9장만 받고 2장 거절
  await fileInput(page).setInputFiles(Array.from({ length: 11 }, (_, i) => jpg(`p${i}.jpg`)))

  await expect(page.getByRole('alert')).toHaveText('사진은 10장까지 올릴 수 있습니다. 2장은 받지 않았습니다.')
  await expect(title(page)).toHaveText('사진 (10/10)')
  expect(posts).toHaveLength(9)
})

test('수정: 9장 있는 글에서 2장을 고르면 1장만 올리고 안내가 뜬다', async ({ page, api }) => {
  // 무엇을: 남은 자리만큼만 올리기 / 왜: 한도를 넘는 요청을 안 보내려고
  setupEdit(api, ids(9))
  const posts = record(page, 'POST', '/api/market-items/1/photos')
  await page.goto('/market/1/edit')
  await expect(title(page)).toHaveText('사진 (9/10)')

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await expect(title(page)).toHaveText('사진 (10/10)')
  await expect(page.getByRole('alert')).toHaveText('사진은 10장까지 올릴 수 있습니다. 1장은 받지 않았습니다.')
  expect(posts).toHaveLength(1)
})

test('수정: 10장이면 [사진 추가]가 비활성이고 안내 글이 보인다', async ({ page, api }) => {
  // 무엇을: 한도 도달 표시 / 왜: 더 못 올리는 이유를 알려 주려고
  setupEdit(api, ids(10))
  await page.goto('/market/1/edit')

  await expect(title(page)).toHaveText('사진 (10/10)')
  await expect(page.getByRole('button', { name: '사진 추가' })).toBeDisabled()
  await expect(page.getByText('사진은 10장까지 올릴 수 있습니다')).toBeVisible()
})

test('수정: 올리는 동안 진행 문구(role=status)가 보이고 버튼이 잠긴다', async ({ page, api }) => {
  // 무엇을: 진행 표시와 중복 조작 방지 / 왜: 오래 걸리는 동안 멈춘 줄 알거나 또 누르지 않게
  setupEdit(api, [1, 2])
  await takeUploads(page, { delayMs: 600 })
  await page.goto('/market/1/edit')

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await expect(page.getByRole('status').filter({ hasText: '사진 올리는 중' })).toHaveText('사진 올리는 중 1/2')
  await expect(page.getByRole('button', { name: '사진 추가' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '사진 1 삭제' })).toBeDisabled()
  // 끝나면 진행 문구가 사라지고 버튼이 되살아난다
  await expect(title(page)).toHaveText('사진 (4/10)')
  await expect(page.getByRole('status').filter({ hasText: '사진 올리는 중' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: '사진 추가' })).toBeEnabled()
})

// ---- 글쓰기 화면 ----

test('글쓰기: 3장 고르고 2번을 빼면 미리보기 2개, 저장하면 글 POST 다음에 사진 POST 2번이 가고 상세로 간다', async ({ page, api }) => {
  // 무엇을: 미리보기·빼기·저장 순서 / 왜: 글이 먼저 있어야 사진을 붙일 수 있어서
  loginVerified(api)
  const order: string[] = []
  page.on('request', (r) => {
    const p = new URL(r.url()).pathname
    if (r.method() === 'POST' && p === '/api/market-items') order.push('글')
    if (r.method() === 'POST' && p === '/api/market-items/7/photos') order.push('사진')
  })
  const photoPosts = record(page, 'POST', '/api/market-items/7/photos')
  api.override({
    'POST /market-items': { status: 201, body: { ...detailItem([]), id: 7 } },
    'GET /market-items/7': { status: 200, body: { ...detailItem([]), id: 7 } },
  })
  await page.goto('/market/new')

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg'), jpg('c.jpg')])
  await expect(page.locator('.market-photo-preview')).toHaveCount(3)
  await expect(title(page)).toHaveText('사진 (3/10)')
  await page.getByRole('button', { name: '사진 2 빼기' }).click() // 확인 창 없이 바로 빠진다
  await expect(page.locator('.market-photo-preview')).toHaveCount(2)
  await expect(title(page)).toHaveText('사진 (2/10)')

  await page.getByLabel('제목').fill('새 자전거')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('5000')
  await page.getByRole('button', { name: '저장' }).click()

  await expect(page).toHaveURL(/\/market\/7$/)
  expect(order).toEqual(['글', '사진', '사진'])
  expect(photoPosts).toHaveLength(2)
  expect(photoPosts[0].body).toContain('filename="a.jpg"') // 빼지 않은 1번·3번이 올라간다
  expect(photoPosts[1].body).toContain('filename="c.jpg"')
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('글쓰기: 글 만들기가 400이면 사진은 올리지 않고 폼 에러가 뜨며 미리보기가 그대로다', async ({ page, api }) => {
  // 무엇을: 글 실패 시 사진 보호 / 왜: 글이 없는데 사진만 올라가면 안 되고, 고른 사진은 다시 안 골라도 되게
  loginVerified(api)
  api.override({ 'POST /market-items': { status: 400 } })
  const photoPosts = record(page, 'POST', '/api/market-items/7/photos')
  const anyPhoto = record(page, 'POST', '/api/market-items/1/photos')
  await page.goto('/market/new')
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])
  await page.getByLabel('제목').fill('새 자전거')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('5000')

  await page.getByRole('button', { name: '저장' }).click()

  await expect(page.getByRole('alert')).toHaveText('제목, 본문, 판매/나눔, 가격을 확인해 주세요 (판매 가격은 1~10,000,000원)')
  await expect(page).toHaveURL(/\/market\/new$/)
  await expect(page.locator('.market-photo-preview')).toHaveCount(2)
  await expect(page.getByRole('button', { name: '사진 추가' })).toBeEnabled()
  expect(photoPosts).toHaveLength(0)
  expect(anyPhoto).toHaveLength(0)
})

test('글쓰기: 사진 2장 중 1장이 실패하면 상세에 "사진 1장을 올리지 못했습니다" 안내가 뜬다', async ({ page, api }) => {
  // 무엇을: 일부 실패 안내 / 왜: 글은 만들어졌으니 상세로 가되 수정에서 다시 올리게 알려야 해서
  loginVerified(api)
  api.override({
    'POST /market-items': { status: 201, body: { ...detailItem([]), id: 7 } },
    'GET /market-items/7': { status: 200, body: { ...detailItem([]), id: 7 } },
  })
  await takeUploads(page, { statuses: [201, 413] })
  await page.goto('/market/new')
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])
  await page.getByLabel('제목').fill('새 자전거')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('5000')

  await page.getByRole('button', { name: '저장' }).click()

  await expect(page).toHaveURL(/\/market\/7$/)
  await expect(page.getByRole('alert')).toHaveText('사진 1장을 올리지 못했습니다. 수정에서 다시 올려 주세요')
})

test('글쓰기: 사진 0장으로 저장하면 사진 요청 없이 기존처럼 상세로 간다', async ({ page, api }) => {
  // 무엇을: 사진 없는 글쓰기 회귀 / 왜: 사진 기능이 기존 흐름을 깨면 안 돼서
  loginVerified(api)
  api.override({
    'POST /market-items': { status: 201, body: { ...detailItem([]), id: 7 } },
    'GET /market-items/7': { status: 200, body: { ...detailItem([]), id: 7 } },
  })
  const photoPosts = record(page, 'POST', '/api/market-items/7/photos')
  await page.goto('/market/new')
  await expect(title(page)).toHaveText('사진 (0/10)')
  await page.getByLabel('제목').fill('새 자전거')
  await page.getByLabel('본문').fill('설명')
  await page.getByLabel('가격(원)').fill('5000')

  await page.getByRole('button', { name: '저장' }).click()

  await expect(page).toHaveURL(/\/market\/7$/)
  expect(photoPosts).toHaveLength(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

// ---- 리뷰 수정 뒤 빈틈 보강 ----

const NETWORK_TEXT = '서버에 연결할 수 없습니다 (2장 실패)' // 멈춘 뒤 남은 장도 실패로 세어 붙는다

for (const c of [
  { status: 403, text: '사진을 올리지 못했습니다. 새로고침 후 다시 시도해 주세요 (2장 실패)' },
  { status: 404, text: '글을 찾을 수 없습니다 (2장 실패)' },
  { status: 409, text: '사진은 10장까지 올릴 수 있고, 거래가 완료된 글에는 올릴 수 없습니다 (2장 실패)' },
]) {
  test(`수정: 2장 중 첫 장이 ${c.status}면 두 번째 요청은 가지 않고 "(2장 실패)"가 붙는다`, async ({ page, api }) => {
    // 무엇을: 같은 이유로 또 실패할 요청 중단 / 왜: 쓸데없는 요청을 막고, 안 올라간 장수까지 알려 주려고
    setupEdit(api, [1])
    const log: string[] = []
    await takeUploads(page, { statuses: [c.status, 201], log })
    const posts = record(page, 'POST', '/api/market-items/1/photos')
    await page.goto('/market/1/edit')

    await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

    await expect(page.getByRole('alert')).toHaveText(c.text)
    expect(log.filter((l) => l.startsWith('start'))).toEqual(['start 1'])
    expect(posts).toHaveLength(1)
    await expect(title(page)).toHaveText('사진 (1/10)')
  })
}

test('수정: 네트워크 오류(연결 끊김)면 두 번째 요청은 가지 않고 연결 안내 문구가 뜬다', async ({ page, api }) => {
  // 무엇을: 응답 없는 오류 중단 / 왜: 서버가 꺼졌으면 다음 장도 똑같이 실패해서
  setupEdit(api, [1])
  api.override({ 'POST /market-items/*/photos': { abort: true } })
  const posts = record(page, 'POST', '/api/market-items/1/photos')
  await page.goto('/market/1/edit')

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await expect(page.getByRole('alert')).toHaveText(NETWORK_TEXT)
  expect(posts).toHaveLength(1)
  await expect(title(page)).toHaveText('사진 (1/10)')
})

test('수정: 올리다 401을 받으면 refresh 뒤 같은 사진을 다시 보내 성공하고 에러가 없다', async ({ page, api }) => {
  // 무엇을: 로그인 자동 갱신 후 재시도 / 왜: 로그인이 잠깐 만료돼도 사진이 올라가야 해서
  setupEdit(api, [1])
  api.override({ 'POST /auth/refresh': { status: 200, body: { ...fakeUser, verifiedSchool: { id: 1, name: '가짜대학교' } } } })
  await takeUploads(page, { statuses: [401, 201] })
  const posts = record(page, 'POST', '/api/market-items/1/photos')
  const refreshes = record(page, 'POST', '/api/auth/refresh')
  await page.goto('/market/1/edit')
  await expect(title(page)).toHaveText('사진 (1/10)')
  const refreshBefore = refreshes.length // 시작 때 부른 것은 빼고 센다

  await fileInput(page).setInputFiles([jpg('a.jpg')])

  await expect(title(page)).toHaveText('사진 (2/10)')
  expect(posts).toHaveLength(2) // 401 한 번 + 재시도 한 번
  expect(posts[1].body).toContain('name="file"')
  expect(posts[1].body).toContain('filename="a.jpg"')
  expect(refreshes.length - refreshBefore).toBe(1)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('수정: 사진을 올리는 동안 글자 [저장]과 숨은 파일 입력이 잠기고 끝나면 풀린다', async ({ page, api }) => {
  // 무엇을: 올리는 중 잠금 / 왜: 사진과 글자 저장이 동시에 일어나 꼬이지 않게
  setupEdit(api, [1])
  await takeUploads(page, { delayMs: 500 })
  await page.goto('/market/1/edit')
  const save = page.getByRole('button', { name: '저장' })
  await expect(save).toBeEnabled()

  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await expect(save).toBeDisabled()
  await expect(page.locator('input[type=file]')).toBeDisabled()
  await expect(title(page)).toHaveText('사진 (3/10)')
  await expect(save).toBeEnabled()
  await expect(page.locator('input[type=file]')).toBeEnabled()
})

test('수정: 사진을 올리는 중 다른 화면으로 이동하면 남은 사진 요청은 가지 않는다', async ({ page, api }) => {
  // 무엇을: 떠나면 중단 / 왜: 화면에 없는 작업이 계속 서버에 요청하면 안 돼서
  setupEdit(api, [1])
  const log: string[] = []
  await takeUploads(page, { delayMs: 500, log })
  await page.goto('/market/1/edit')
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg'), jpg('c.jpg')])
  await expect.poll(() => log).toContain('start 1')

  await page.getByRole('link', { name: '비자 후기', exact: true }).click() // 앱 안에서 이동 (화면이 사라진다)
  await expect(page).toHaveURL(/\/visa-reviews$/)
  await expect.poll(() => log).toContain('end 1') // 첫 응답이 온 뒤에도
  await page.waitForTimeout(400) // 다음 요청이 나갈 만큼 기다려 본다

  expect(log.filter((l) => l.startsWith('start'))).toEqual(['start 1'])
})

test('글쓰기: 글 뒤 사진을 올리는 동안 [취소]는 비활성이고 진행 문구가 보인다', async ({ page, api }) => {
  // 무엇을: 저장 중 취소 막기 / 왜: 도중에 나가면 글만 남고 사진이 빠지는 걸 줄이려고
  setupCreate(api)
  await takeUploads(page, { delayMs: 500 })
  await page.goto('/market/new')
  await expect(page.getByRole('link', { name: '취소' })).toBeVisible() // 저장 전에는 링크
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await fillAndSave(page)

  await expect(page.getByRole('button', { name: '취소' })).toBeDisabled()
  await expect(page.getByRole('link', { name: '취소' })).toHaveCount(0)
  await expect(page.getByRole('status').filter({ hasText: '사진 올리는 중' })).toHaveText('사진 올리는 중 1/2')
  await expect(page.locator('input[type=file]')).toBeDisabled()
  await expect(page).toHaveURL(/\/market\/7$/)
})

test('글쓰기: 사진을 올리는 중 다른 화면으로 이동하면 남은 사진은 안 보내고 상세로 끌려가지 않는다', async ({ page, api }) => {
  // 무엇을: 떠나면 중단·이동 안 함 / 왜: 사용자가 이미 다른 화면에 있는데 갑자기 상세로 튀면 안 돼서
  setupCreate(api)
  const log: string[] = []
  await takeUploads(page, { delayMs: 500, log })
  await page.goto('/market/new')
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])
  await fillAndSave(page)
  await expect.poll(() => log).toContain('start 1')

  await page.getByRole('link', { name: '비자 후기', exact: true }).click()
  await expect(page).toHaveURL(/\/visa-reviews$/)
  await expect.poll(() => log).toContain('end 1')
  await page.waitForTimeout(400)

  expect(log.filter((l) => l.startsWith('start'))).toEqual(['start 1'])
  await expect(page).toHaveURL(/\/visa-reviews$/) // 상세(/market/7)로 끌려가지 않았다
})

test('글쓰기: 사진이 전부 실패하면 상세에 "사진 2장을 올리지 못했습니다" 안내가 뜬다', async ({ page, api }) => {
  // 무엇을: 전부 실패 안내 / 왜: 글만 만들어졌다는 걸 알려 수정에서 다시 올리게
  setupCreate(api)
  await takeUploads(page, { statuses: [413, 413] })
  await page.goto('/market/new')
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await fillAndSave(page)

  await expect(page).toHaveURL(/\/market\/7$/)
  await expect(page.getByRole('alert')).toHaveText('사진 2장을 올리지 못했습니다. 수정에서 다시 올려 주세요')
})

test('글쓰기: 사진 단계에서 401이면 남은 사진은 안 보내고 실패 장수 안내로 상세에 간다', async ({ page, api }) => {
  // 무엇을: 401 중단 / 왜: 로그인이 풀렸으면 다음 장도 실패해서
  setupCreate(api)
  api.override({ 'POST /auth/refresh': { status: 401 } })
  const log: string[] = []
  await takeUploads(page, { statuses: [401, 201], log })
  await page.goto('/market/new')
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])

  await fillAndSave(page)

  await expect(page).toHaveURL(/\/market\/7$/)
  await expect(page.getByRole('alert').filter({ hasText: '사진' })).toHaveText('사진 2장을 올리지 못했습니다. 수정에서 다시 올려 주세요')
  expect(log.filter((l) => l.startsWith('start'))).toEqual(['start 1'])
})

test('글쓰기: 사진 1장 실패 안내는 새로고침하면 사라진다', async ({ page, api }) => {
  // 무엇을: 안내가 기록에 안 남음 / 왜: F5를 눌렀는데 옛 안내가 또 뜨면 헷갈려서
  setupCreate(api)
  await takeUploads(page, { statuses: [201, 413] })
  await page.goto('/market/new')
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])
  await fillAndSave(page)
  await expect(page).toHaveURL(/\/market\/7$/)
  await expect(page.getByRole('alert')).toHaveText('사진 1장을 올리지 못했습니다. 수정에서 다시 올려 주세요')
  // 상세가 기록의 state를 지울 때까지 기다린다
  await expect.poll(() => page.evaluate<unknown>('window.history.state?.usr ?? null')).toBeNull()

  await page.reload()

  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('글쓰기: 사진 실패 안내는 다른 화면에 갔다가 뒤로·앞으로 가도 다시 뜨지 않는다', async ({ page, api }) => {
  // 무엇을: 뒤로가기/앞으로가기 / 왜: 기록에 남은 state 때문에 안내가 되살아나면 안 돼서
  setupCreate(api)
  api.override({ 'GET /market-items': { status: 200, body: [] } })
  await takeUploads(page, { statuses: [201, 413] })
  await page.goto('/market/new')
  await fileInput(page).setInputFiles([jpg('a.jpg'), jpg('b.jpg')])
  await fillAndSave(page)
  await expect(page.getByRole('alert')).toHaveText('사진 1장을 올리지 못했습니다. 수정에서 다시 올려 주세요')
  await expect.poll(() => page.evaluate<unknown>('window.history.state?.usr ?? null')).toBeNull()

  await page.getByRole('link', { name: '중고·나눔', exact: true }).click() // 앱 안에서 다른 화면으로
  await expect(page).toHaveURL(/\/market$/)
  await page.goBack() // 상세로 돌아옴
  await expect(page).toHaveURL(/\/market\/7$/)
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await page.goForward()
  await expect(page).toHaveURL(/\/market$/)
  await page.goBack()
  await expect(page.getByRole('heading', { name: '가짜 자전거' })).toBeVisible()
  await expect(page.getByRole('alert')).toHaveCount(0)
})

// ---- 수정 화면 잠금 (사진 <-> 글자 저장) ----

// 응답을 우리가 열어 줄 때까지 붙잡아 두는 문. 시간(ms) 추측 없이 "지연 중" 상태를 확정해서 확인한다
function makeGate() {
  let open!: () => void
  const opened = new Promise<void>((r) => {
    open = r
  })
  return { opened, open }
}

// 글자 [저장] 버튼 (저장 중에는 글자가 "저장 중..."으로 바뀌므로 type=submit으로 찾는다)
const submitButton = (page: Page) => page.locator('form.panel button[type=submit]')

test('수정: 사진 삭제 중(DELETE 응답 지연)에는 글자 [저장]이 비활성이고 끝나면 다시 활성이다', async ({ page, api }) => {
  // 무엇을: 사진 삭제 중 글자 저장 잠금 / 왜: 삭제와 글자 저장이 동시에 일어나 꼬이지 않게
  setupEdit(api, [1, 2])
  const gate = makeGate()
  await page.route(
    (url) => url.pathname === '/api/market-items/1/photos/2',
    async (route) => {
      if (route.request().method() !== 'DELETE') return route.fallback()
      await gate.opened
      await route.fulfill({ status: 204 })
    },
  )
  await page.goto('/market/1/edit')
  await expect(title(page)).toHaveText('사진 (2/10)')
  await expect(submitButton(page)).toBeEnabled()

  await page.getByRole('button', { name: '사진 2 삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(submitButton(page)).toBeDisabled() // 응답이 아직 안 왔다
  await expect(title(page)).toHaveText('사진 (2/10)')
  gate.open()
  await expect(title(page)).toHaveText('사진 (1/10)')
  await expect(submitButton(page)).toBeEnabled()
})

test('수정: 글자 저장(PUT 응답 지연) 중에는 [사진 추가]·사진 삭제 버튼·숨긴 파일 입력이 비활성이다', async ({ page, api }) => {
  // 무엇을: 글자 저장 중 사진 영역 잠금 / 왜: 저장 도중 사진을 바꾸면 결과가 꼬일 수 있어서
  setupEdit(api, [1, 2])
  const gate = makeGate()
  await page.route(
    (url) => url.pathname === '/api/market-items/1',
    async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback()
      await gate.opened
      await route.fulfill({ status: 200, json: detailItem([1, 2]) })
    },
  )
  await page.goto('/market/1/edit')
  await expect(title(page)).toHaveText('사진 (2/10)')
  await expect(page.getByRole('button', { name: '사진 추가' })).toBeEnabled()
  await expect(page.getByRole('button', { name: '사진 1 삭제' })).toBeEnabled()
  await expect(fileInput(page)).toBeEnabled()

  await submitButton(page).click()

  await expect(submitButton(page)).toHaveText('저장 중...') // 응답이 아직 안 왔다
  await expect(page.getByRole('button', { name: '사진 추가' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '사진 1 삭제' })).toBeDisabled()
  await expect(page.getByRole('button', { name: '사진 2 삭제' })).toBeDisabled()
  await expect(fileInput(page)).toBeDisabled()
  gate.open()
  await expect(page).toHaveURL(/\/market\/1$/) // 저장이 끝나면 상세로 간다
})

test('수정: 글자 저장 중에는 [취소]가 링크가 아닌 비활성 버튼이다', async ({ page, api }) => {
  // 무엇을: 저장 중 취소 막기 / 왜: 링크는 disabled가 안 돼서 저장 중 나가는 걸 막으려고 버튼으로 바꾼다
  setupEdit(api, [1])
  const gate = makeGate()
  await page.route(
    (url) => url.pathname === '/api/market-items/1',
    async (route) => {
      if (route.request().method() !== 'PUT') return route.fallback()
      await gate.opened
      await route.fulfill({ status: 200, json: detailItem([1]) })
    },
  )
  await page.goto('/market/1/edit')
  await expect(page.getByRole('link', { name: '취소' })).toBeVisible() // 저장 전에는 링크

  await submitButton(page).click()

  await expect(page.getByRole('button', { name: '취소' })).toBeDisabled()
  await expect(page.getByRole('link', { name: '취소' })).toHaveCount(0)
  gate.open()
  await expect(page).toHaveURL(/\/market\/1$/)
})

// ---- 공통 ----

// 키보드: [사진 추가]에 Tab으로 닿고 Enter를 누르면 숨긴 파일 입력이 눌린다.
// 파일 선택 창(filechooser) 이벤트는 키보드로 열 때 부하가 있으면 가끔 안 잡혀서(버튼 클릭은 항상 잡힘),
// 숨긴 입력의 click 이벤트로 판정한다
async function watchInputClick(page: Page) {
  await page.evaluate(
    "window.__inputClicks = 0; document.querySelector('input[type=file]').addEventListener('click', () => { window.__inputClicks++ })",
  )
}
const inputClicks = (page: Page) => page.evaluate<number>('window.__inputClicks')

test('[사진 추가] 버튼은 Tab만으로 닿고 Enter를 누르면 파일 입력이 열린다', async ({ page, api }) => {
  // 무엇을: 키보드 조작 / 왜: 마우스 없이도 사진을 고를 수 있어야 해서 (숨긴 입력은 tabIndex -1이라 버튼이 대신 대상)
  setupEdit(api, [])
  await page.goto('/market/1/edit')
  await expect(title(page)).toBeVisible()
  await api.waitIdle() // 화면이 다시 그려질 요청(로그인 확인 등)이 끝난 뒤에 Tab을 누른다
  await watchInputClick(page)
  const add = page.getByRole('button', { name: '사진 추가' })
  for (let i = 0; i < 30; i++) {
    await page.keyboard.press('Tab')
    if (await page.evaluate<boolean>("document.activeElement?.textContent?.trim() === '사진 추가'")) break
  }
  await expect(add).toBeFocused()

  await page.keyboard.press('Enter')

  await expect.poll(() => inputClicks(page)).toBe(1)
})

test('글쓰기 화면에서도 [사진 추가]를 Enter로 누르면 파일 입력이 열린다', async ({ page, api }) => {
  // 무엇을: 공용 사진 영역의 키보드 조작(글쓰기) / 왜: 두 화면이 같은 부품이라도 각각 확인
  loginVerified(api)
  await page.goto('/market/new')
  const add = page.getByRole('button', { name: '사진 추가' })
  await expect(add).toBeVisible()
  await api.waitIdle()
  await watchInputClick(page)
  await add.focus()
  await expect(add).toBeFocused()

  await page.keyboard.press('Enter')

  await expect.poll(() => inputClicks(page)).toBe(1)
})

test('375px 폭에서 수정 화면(사진 10장)과 글쓰기 화면(미리보기 여러 장)에 가로 스크롤이 없다', async ({ page, api }) => {
  // 무엇을: 모바일 폭 가로 스크롤 / 왜: 사진 목록이 화면 밖으로 삐져나오면 안 돼서
  setupEdit(api, ids(10))
  await page.setViewportSize({ width: 375, height: 800 })
  await page.goto('/market/1/edit')
  await expect(title(page)).toHaveText('사진 (10/10)')
  await expect(page.locator('.market-photo-preview')).toHaveCount(10)
  expect(await hasHorizontalScroll(page)).toBe(false)

  await page.goto('/market/new')
  await fileInput(page).setInputFiles(Array.from({ length: 10 }, (_, i) => jpg(`p${i}.jpg`)))
  await expect(page.locator('.market-photo-preview')).toHaveCount(10)
  expect(await hasHorizontalScroll(page)).toBe(false)
})
