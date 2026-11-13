import { test, expect, loginVerified, loginAdmin, fakeConversationDetail, fakeConversationSummary } from './mockApi.ts'
import type { AdminConversationView, ConversationDetail } from '../src/types.ts'

// S14-h 보충: unread-badge-hasmore.spec.ts 에 없는 완료 조건(재조회, 기존 동작 유지, 0통, 탈퇴 상대, 긴 닉네임, 모바일 폭)

const summary = (id: number, unreadCount: number) => ({ ...fakeConversationSummary, id, unreadCount })

test('대화를 열었다가 쪽지함으로 돌아오면 목록을 다시 불러와 배지가 바뀐 값으로 보인다', async ({ page, api }) => {
  loginVerified(api)
  // 개발 모드는 효과가 두 번 돌 수 있어 호출 횟수 대신 "대화 3번을 열었는가"로 응답을 바꾼다 (열기 전 5통, 연 뒤 0통)
  let opened = false
  api.override({
    'GET /conversations': () => ({ status: 200, body: [summary(3, opened ? 0 : 5)] }),
    'GET /conversations/3': () => {
      opened = true
      return { status: 200, body: fakeConversationDetail }
    },
  })
  await page.goto('/messages')
  await expect(page.locator('.row .badge-unread [aria-hidden="true"]')).toHaveText('5')
  await page.locator('.row .conv-link').click()
  await expect(page).toHaveURL(/\/messages\/3$/)
  await page.getByRole('link', { name: '쪽지함으로' }).click()
  await expect(page.locator('.row')).toHaveCount(1)
  await expect(page.locator('.row .badge-unread')).toHaveCount(0)
})

test('hasMore 안내가 떠 있어도 답장을 보내면 새 쪽지가 목록에 붙고 신고 버튼도 그대로 있다', async ({ page, api }) => {
  loginVerified(api)
  const detail: ConversationDetail = { ...fakeConversationDetail, hasMore: true }
  api.override({ 'GET /conversations/3': { status: 200, body: detail } })
  await page.goto('/messages/3')
  await expect(page.locator('.msg-truncated')).toBeVisible()
  await page.getByLabel('답장 내용').fill('답장입니다')
  await page.getByRole('button', { name: '답장 보내기' }).click()
  await expect(page.locator('.msg-list').getByText('답장입니다')).toBeVisible()
  // 안내는 계속 보이고, 신고 영역(.actions)도 남아 있다
  await expect(page.locator('.msg-truncated')).toBeVisible()
  await expect(page.locator('.actions')).toBeVisible()
})

const emptyAdminView: AdminConversationView = {
  reportId: 7,
  conversationId: 12,
  itemTitle: '중고 자전거 팝니다',
  buyer: { id: 2, nickname: '신고자' },
  seller: { id: 8, nickname: '판매자' },
  messages: [],
  hasMore: false,
}

test('관리자 대화 보기: 쪽지 0통 + hasMore false면 빈 안내만 있고 잘림 안내는 없다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { status: 200, body: emptyAdminView } })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.getByText('아직 쪽지가 없습니다')).toBeVisible()
  await expect(page.locator('.msg-truncated')).toHaveCount(0)
})

test('탈퇴한 상대(partner null) 줄에도 배지가 보이고 접근성 이름에 개수가 들어간다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations': { status: 200, body: [{ ...summary(3, 7), partner: null }] } })
  await page.goto('/messages')
  const row = page.locator('.row')
  await expect(row.locator('.badge-unread [aria-hidden="true"]')).toHaveText('7')
  await expect(row.getByRole('link')).toHaveAccessibleName(/안 읽은 쪽지 7개/)
})

test('아주 긴 닉네임이어도 배지가 줄 밖으로 밀려나지 않는다', async ({ page, api }) => {
  loginVerified(api)
  const longName = '가'.repeat(200)
  api.override({
    'GET /conversations': {
      status: 200,
      body: [{ ...summary(3, 12), partner: { id: 7, nickname: longName } }],
    },
  })
  await page.goto('/messages')
  const row = page.locator('.row')
  const badge = row.locator('.badge-unread')
  await expect(badge).toBeVisible()
  const rowBox = (await row.boundingBox())!
  const badgeBox = (await badge.boundingBox())!
  // 배지의 오른쪽 끝이 줄의 오른쪽 끝을 넘지 않고, 왼쪽도 줄 안에 있다
  expect(badgeBox.x).toBeGreaterThanOrEqual(rowBox.x)
  expect(badgeBox.x + badgeBox.width).toBeLessThanOrEqual(rowBox.x + rowBox.width + 1)
})

test.describe('모바일 폭 375px', () => {
  test.use({ viewport: { width: 375, height: 700 } })

  // 페이지 전체가 옆으로 스크롤되는지(가로 스크롤) 확인
  const hasHScroll = (page: import('@playwright/test').Page) =>
    page.evaluate<boolean>('document.documentElement.scrollWidth > document.documentElement.clientWidth')

  test('쪽지함: 99+ 배지와 긴 닉네임이 있어도 가로 스크롤이 생기지 않는다', async ({ page, api }) => {
    loginVerified(api)
    api.override({
      'GET /conversations': {
        status: 200,
        body: [{ ...summary(3, 150), partner: { id: 7, nickname: '가'.repeat(200) } }, summary(2, 99)],
      },
    })
    await page.goto('/messages')
    await expect(page.locator('.badge-unread').first()).toBeVisible()
    expect(await hasHScroll(page)).toBe(false)
  })

  test('회원·관리자 대화 화면: 안내 문구가 있어도 가로 스크롤이 생기지 않는다', async ({ page, api }) => {
    loginAdmin(api)
    const detail: ConversationDetail = { ...fakeConversationDetail, hasMore: true }
    api.override({
      'GET /conversations/3': { status: 200, body: detail },
      'GET /admin/reports/7/conversation': { status: 200, body: { ...emptyAdminView, hasMore: true } },
    })
    await page.goto('/messages/3')
    await expect(page.locator('.msg-truncated')).toBeVisible()
    expect(await hasHScroll(page)).toBe(false)
    await page.goto('/admin/reports/7/conversation')
    await expect(page.locator('.msg-truncated')).toBeVisible()
    expect(await hasHScroll(page)).toBe(false)
  })
})
