import { expect, FIRST_LOAD, fakeMarketItem, fakeUser, makePng, test, typeInto, verifiedUser } from './mockApi'
import type { FakeUser } from './mockApi'

// S30-c 사진 자동 검사 화면 (진짜 브라우저):
// 1) 관리자 신고 목록에서 AUTO 신고가 '자동 탐지'로 보인다 (USER 신고는 닉네임)
// 2) 중고 글 수정 화면에서 사진 올리기가 422면 거절 문구가 보이고, 사진 칸 아래 구글 전송 안내가 있다

const admin: FakeUser = { ...fakeUser, id: 9, username: 'admin', nickname: '관리자', role: 'ADMIN' }

const report = (id: number, source: string | undefined, reporter: { id: number; nickname: string } | null) => ({
  id,
  ...(source === undefined ? {} : { source }),
  target: { type: 'MARKET_ITEM', id: 42, postId: null, preview: `신고된 중고 글 ${id}`, author: { id: 5, nickname: '다른사람' } },
  reporter,
  reason: { code: 'SPAM', name: '스팸' },
  detail: null,
  status: 'PENDING',
  createdAt: '2026-11-12T00:00:00Z',
})

test('관리자 신고 목록에서 AUTO 신고는 "자동 탐지" 배지와 신고자 줄로, USER 신고는 닉네임으로 보인다', async ({ page, api }) => {
  api.setUser(admin)
  api.override({
    'GET /admin/reports': {
      status: 200,
      body: [report(1, 'AUTO', null), report(2, 'USER', { id: 2, nickname: '민수' })],
    },
  })

  await page.goto('/admin/reports')
  await expect(page.getByText('신고된 중고 글 1')).toBeVisible({ timeout: FIRST_LOAD })
  await expect(page.getByText('신고된 중고 글 2')).toBeVisible()
  // AUTO 한 건: 배지 + 신고자 줄 = '자동 탐지' 글자 2곳. USER 행에는 없다 (전체에서 정확히 2곳)
  await expect(page.getByText('자동 탐지', { exact: true })).toHaveCount(2)
  await expect(page.getByText('민수', { exact: true })).toBeVisible()
  expect(api.sent('GET', '/admin/reports')).toHaveLength(1)
})

// 글쓰기 경로: 사진 1장이 422(자동 검사 거절)면 글은 만들어지고 상세로 가서, "다시 올려 주세요" 없는 거절 안내가 뜬다
test('글쓰기에서 사진이 422면 상세로 이동해 "판단된 사진이 1장 있습니다" 안내가 뜬다', async ({ page, api }) => {
  api.setUser(verifiedUser)
  const mine = { ...fakeMarketItem, seller: { id: 1, nickname: '테스터' }, photos: [] }
  api.override({
    'POST /market-items': { status: 201, body: mine },
    'GET /market-items/1': { status: 200, body: mine },
    'POST /market-items/*/photos': { status: 422, body: { message: 'rejected' } },
  })

  await page.goto('/market/new')
  await expect(page.getByRole('textbox', { name: /제목/ })).toBeVisible({ timeout: FIRST_LOAD })
  await typeInto(page, /제목/, '거절될 사진 글')
  await typeInto(page, /가격/, '5000')
  await typeInto(page, /본문/, '사진 한 장이 거절됩니다')

  const chooserPromise = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: '사진 추가' }).click()
  const chooser = await chooserPromise
  await chooser.setFiles([{ name: 'a.png', mimeType: 'image/png', buffer: makePng(200, 60, 60) }])
  await expect(page.getByText('사진 (1/10)')).toBeVisible()

  await page.getByRole('button', { name: '저장' }).click()

  await expect(page).toHaveURL('/market/1')
  await expect(page.getByRole('alert')).toHaveAccessibleName('자동 검사에서 올릴 수 없는 사진으로 판단된 사진이 1장 있습니다')
  await expect(page.getByText('다시 올려 주세요')).toHaveCount(0) // 거절은 다시 올려도 또 거절이라 이 말이 없다
  expect(api.sent('POST', '/market-items/1/photos')).toHaveLength(1)
})

test('중고 글 수정에서 사진 올리기가 422면 자동 검사 거절 문구가 보이고, 사진 칸 아래 구글 전송 안내가 있다', async ({ page, api }) => {
  api.setUser(verifiedUser)
  const mine = { ...fakeMarketItem, seller: { id: 1, nickname: '테스터' }, photos: [] }
  api.override({
    'GET /market-items/1': { status: 200, body: mine },
    'POST /market-items/*/photos': { status: 422, body: { message: 'rejected' } },
  })

  await page.goto('/market/1/edit')
  await expect(page.getByText('사진 (0/10)')).toBeVisible({ timeout: FIRST_LOAD })
  await expect(page.getByText('올린 사진은 자동 검사를 위해 구글로 전송돼요.')).toBeVisible()

  const chooserPromise = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: '사진 추가' }).click()
  const chooser = await chooserPromise
  await chooser.setFiles([{ name: 'a.png', mimeType: 'image/png', buffer: makePng(200, 60, 60) }])

  // 에러 안내는 role="alert"이고 글자는 접근 가능한 이름으로 나온다 (error-messages.spec 방식)
  await expect(page.getByRole('alert')).toHaveAccessibleName('자동 검사에서 올릴 수 없는 사진으로 판단되었습니다. 다른 사진을 골라 주세요')
  await expect(page.getByText('사진 (0/10)')).toBeVisible() // 목록은 그대로
  expect(api.sent('POST', '/market-items/1/photos')).toHaveLength(1)
})
