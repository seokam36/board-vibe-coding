import { test, expect, loginVerified, loginAdmin, fakeConversationDetail, fakeConversationSummary } from './mockApi.ts'
import type { AdminConversationView, ConversationDetail } from '../src/types.ts'

// S14-h: 쪽지함 줄의 안 읽은 수 배지 + "이전 쪽지는 표시되지 않아요"(hasMore) 안내. 서버 응답은 mockApi 가짜 응답이다

const summary = (id: number, unreadCount: number) => ({ ...fakeConversationSummary, id, unreadCount })

test('안 읽은 쪽지가 있는 줄에만 배지가 보이고, 0이면 배지가 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations': { status: 200, body: [summary(3, 3), summary(2, 0)] } })
  await page.goto('/messages')
  const rows = page.locator('.row')
  await expect(rows).toHaveCount(2)
  await expect(rows.nth(0).locator('.badge-unread [aria-hidden="true"]')).toHaveText('3')
  await expect(rows.nth(1).locator('.badge-unread')).toHaveCount(0)
})

test('배지 숫자: 99는 그대로, 100은 99+, 읽기 프로그램에는 실제 개수 문장', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations': { status: 200, body: [summary(3, 99), summary(2, 100)] } })
  await page.goto('/messages')
  const rows = page.locator('.row')
  // 눈에 보이는 숫자(aria-hidden)
  await expect(rows.nth(0).locator('.badge-unread [aria-hidden="true"]')).toHaveText('99')
  await expect(rows.nth(1).locator('.badge-unread [aria-hidden="true"]')).toHaveText('99+')
  // 접근성 이름: 줄 링크의 이름에 "안 읽은 쪽지 N개"가 들어간다
  await expect(rows.nth(0).getByRole('link')).toHaveAccessibleName(/안 읽은 쪽지 99개/)
  await expect(rows.nth(1).getByRole('link')).toHaveAccessibleName(/안 읽은 쪽지 100개/)
  await expect(rows.nth(1).getByRole('link')).not.toHaveAccessibleName(/99\+/)
})

test('회원 대화 화면: hasMore가 true면 쪽지 목록 위에 안내가 보인다', async ({ page, api }) => {
  loginVerified(api)
  const detail: ConversationDetail = { ...fakeConversationDetail, hasMore: true }
  api.override({ 'GET /conversations/3': { status: 200, body: detail } })
  await page.goto('/messages/3')
  const note = page.getByText('이전 쪽지는 표시되지 않아요')
  await expect(note).toBeVisible()
  // 안내가 쪽지 목록보다 위에 있다
  const noteBox = await note.boundingBox()
  const listBox = await page.locator('.msg-list').boundingBox()
  expect(noteBox).not.toBeNull()
  expect(listBox).not.toBeNull()
  expect(noteBox!.y).toBeLessThan(listBox!.y)
  // 안내 문구는 기존 회색 보조 글씨(.meta)와 같은 색·크기다 (.hint엔 색이 없어 .msg-truncated가 정한다)
  await expect(note).toHaveClass(/msg-truncated/)
  // e2e 설정엔 DOM 타입이 없어 문자열로 실행한다
  const meta = await page.evaluate<{ color: string; size: string }>(
    `(() => { const s = getComputedStyle(document.querySelector('.meta')); return { color: s.color, size: s.fontSize } })()`,
  )
  await expect(note).toHaveCSS('color', meta.color)
  await expect(note).toHaveCSS('font-size', meta.size)
})

test('회원 대화 화면: hasMore가 false면 안내가 없다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).not.toHaveCount(0)
  await expect(page.getByText('이전 쪽지는 표시되지 않아요')).toHaveCount(0)
})

const adminView: AdminConversationView = {
  reportId: 7,
  conversationId: 12,
  itemTitle: '중고 자전거 팝니다',
  buyer: { id: 2, nickname: '신고자' },
  seller: { id: 8, nickname: '판매자' },
  messages: [{ id: 30, sender: { id: 2, nickname: '신고자' }, content: '아직 있나요?', createdAt: '2026-11-13T03:00:00Z' }],
  hasMore: false,
}

test('관리자 대화 보기: hasMore가 true면 "최근 200통만 보여요" 안내', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { status: 200, body: { ...adminView, hasMore: true } } })
  await page.goto('/admin/reports/7/conversation')
  const note = page.getByText('최근 200통만 보여요. 이전 쪽지는 표시되지 않아요')
  await expect(note).toBeVisible()
  // 회원 화면과 같은 클래스라 같은 회색·작은 글씨다
  await expect(note).toHaveClass(/msg-truncated/)
  await expect(note).toHaveCSS('color', 'rgb(154, 167, 161)') // --muted #9aa7a1
  await expect(note).toHaveCSS('font-size', '13px') // --fs-small
})

test('관리자 대화 보기: hasMore가 false면 안내가 없다', async ({ page, api }) => {
  loginAdmin(api)
  api.override({ 'GET /admin/reports/7/conversation': { status: 200, body: adminView } })
  await page.goto('/admin/reports/7/conversation')
  await expect(page.locator('.msg')).toHaveCount(1)
  await expect(page.getByText('이전 쪽지는 표시되지 않아요')).toHaveCount(0)
})
