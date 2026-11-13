import { test, expect, fakeConversationDetail, loginVerified } from './mockApi.ts'
import type { Page } from '@playwright/test'
import type { ConversationDetail } from '../src/types.ts'

// S14-f3: 대화 화면의 [신고] 버튼. 서버 응답은 mockApi 가짜 응답이다.
// 확인할 것: 성공·사유 없음·409·404·401·403 안내, 탈퇴/삭제된 방에도 버튼, 신고 뒤에도 대화 유지, 대화 5→3 이동 때 올바른 주소, 상단바 읽음 흐름

const RELOGIN = '다시 로그인해 주세요 로그인' // 안내 문구 + 로그인 링크 글자

// 신고 POST로 나간 요청(주소·본문)을 모은다
function recordReports(page: Page) {
  const calls: { path: string; body: string | null }[] = []
  page.on('request', (r) => {
    const path = new URL(r.url()).pathname
    if (r.method() === 'POST' && path.endsWith('/reports')) calls.push({ path, body: r.postData() })
  })
  return calls
}

// 지정한 메서드·경로로 나간 요청 수를 센다
function count(page: Page, method: string, pathname: string) {
  const calls: string[] = []
  page.on('request', (r) => {
    if (r.method() === method && new URL(r.url()).pathname === pathname) calls.push(r.url())
  })
  return calls
}

const reportButton = (page: Page) => page.getByRole('button', { name: '대화 신고' })

// 신고 양식을 열고 사유를 골라 보낸다
async function sendReport(page: Page, reason = 'SPAM') {
  await reportButton(page).click()
  await page.getByLabel('신고 사유').selectOption(reason)
  await page.getByRole('button', { name: '신고 보내기' }).click()
}

test('대화 화면에서 신고하면 POST /api/conversations/3/reports가 {reason, detail}로 나가고 성공 문구가 보이며 양식이 닫힌다', async ({ page, api }) => {
  loginVerified(api)
  const calls = recordReports(page)
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  await reportButton(page).click()
  await page.getByLabel('신고 사유').selectOption('ABUSE')
  await page.getByLabel('신고 상세 내용 (선택)').fill('  반복해서 욕설을 보냅니다  ')
  await page.getByRole('button', { name: '신고 보내기' }).click()

  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  // 주소에 대화 번호가 빠지거나 다른 곳으로 나가면 안 된다. 상세 내용은 앞뒤 공백을 뗀다
  expect(calls.map((c) => c.path)).toEqual(['/api/conversations/3/reports'])
  expect(JSON.parse(calls[0].body ?? '{}')).toEqual({ reason: 'ABUSE', detail: '반복해서 욕설을 보냅니다' })
  // 접수 뒤에는 양식이 닫히고 [신고] 버튼도 다시 안 나온다 (중복 신고 방지)
  await expect(page.getByRole('button', { name: '신고 보내기' })).toHaveCount(0)
  await expect(reportButton(page)).toHaveCount(0)
})

test('상세 내용을 비우면 본문은 {reason}뿐이다', async ({ page, api }) => {
  loginVerified(api)
  const calls = recordReports(page)
  await page.goto('/messages/3')
  await sendReport(page, 'SPAM')
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  expect(JSON.parse(calls[0].body ?? '{}')).toEqual({ reason: 'SPAM' })
})

test('신고한 뒤에도 대화 쪽지와 답장 칸은 그대로 보인다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  await sendReport(page)
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect(page.locator('.msg').nth(1)).toContainText('아직 있어요')
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
  await expect(page.getByLabel('답장 내용')).toBeVisible()
})

test('사유를 안 고르고 보내면 안내만 나오고 요청은 나가지 않는다', async ({ page, api }) => {
  loginVerified(api)
  const calls = recordReports(page)
  await page.goto('/messages/3')
  await reportButton(page).click()
  await page.getByRole('button', { name: '신고 보내기' }).click()
  await expect(page.getByRole('alert')).toHaveText('신고 사유를 골라 주세요')
  await api.waitIdle()
  expect(calls).toHaveLength(0)
})

test('이미 신고한 대화면(409) "이미 신고한 대화입니다"가 보이고 양식이 닫힌다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'POST /conversations/3/reports': { status: 409 } })
  await page.goto('/messages/3')
  await sendReport(page)
  await expect(page.getByRole('alert')).toHaveText('이미 신고한 대화입니다')
  await expect(page.getByRole('button', { name: '신고 보내기' })).toHaveCount(0)
  await expect(page.locator('.msg')).toHaveCount(2)
})

test('신고가 404면 "대화를 찾을 수 없습니다"가 보이고 대화 화면은 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'POST /conversations/3/reports': { status: 404 } })
  await page.goto('/messages/3')
  await sendReport(page)
  await expect(page.getByRole('alert')).toHaveText('대화를 찾을 수 없습니다')
  await expect(page.locator('.msg')).toHaveCount(2)
})

test('신고가 400이면 사유 안내가 보인다 ("자기 것" 문구는 없다)', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'POST /conversations/3/reports': { status: 400 } })
  await page.goto('/messages/3')
  await sendReport(page)
  await expect(page.getByRole('alert')).toHaveText('신고 사유를 확인해 주세요')
})

test('신고가 403이면 학교 인증·새로고침 안내가 보인다 ("인증한 학교의 글" 문구는 없다)', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'POST /conversations/3/reports': { status: 403 } })
  await page.goto('/messages/3')
  await sendReport(page)
  await expect(page.getByRole('alert')).toHaveText('학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요')
})

test('신고가 401이고 재발급도 401이면 다시 로그인 안내가 보이고 쪽지 목록과 쓰던 답장은 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'POST /conversations/3/reports': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/messages/3')
  await page.getByLabel('답장 내용').fill('쓰던 답장')
  await sendReport(page)
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  await expect(page.getByLabel('답장 내용')).toHaveValue('쓰던 답장')
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect(page).toHaveURL(/\/messages\/3$/)
  // 로그인이 풀리면 [신고] 버튼 영역도 사라진다
  await expect(reportButton(page)).toHaveCount(0)
  await expect(page.getByRole('button', { name: '신고 보내기' })).toHaveCount(0)
})

test('상대가 탈퇴한 방에도 [신고] 버튼이 있고 신고할 수 있다', async ({ page, api }) => {
  loginVerified(api)
  const detail: ConversationDetail = { ...fakeConversationDetail, partner: null }
  api.override({ 'GET /conversations/3': { status: 200, body: detail } })
  const calls = recordReports(page)
  await page.goto('/messages/3')
  await expect(page.getByText('상대가 탈퇴해 더 이상 쪽지를 보낼 수 없어요')).toBeVisible()
  await expect(reportButton(page)).toBeVisible()
  await sendReport(page)
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  expect(calls.map((c) => c.path)).toEqual(['/api/conversations/3/reports'])
})

test('글이 삭제된 방에도 [신고] 버튼이 있다', async ({ page, api }) => {
  loginVerified(api)
  const detail: ConversationDetail = { ...fakeConversationDetail, item: { id: null, title: '가짜 자전거', deleted: true } }
  api.override({ 'GET /conversations/3': { status: 200, body: detail } })
  await page.goto('/messages/3')
  await expect(page.getByText('삭제된 글')).toBeVisible()
  await expect(reportButton(page)).toBeVisible()
})

test('학교 인증을 안 한 회원에게는 [신고] 버튼이 없고 답장 칸 대신 인증 안내가 보인다', async ({ page, api }) => {
  api.setLoggedIn(true) // 로그인만 하고 학교 인증은 없음
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect(page.getByText('학교 인증 후 답장할 수 있어요')).toBeVisible()
  await expect(reportButton(page)).toHaveCount(0)
})

test('대화 3에서 열어 둔 신고 양식은 대화 5로 가면 사라지고, 3으로 돌아와 보낸 신고는 3번 주소로만 간다', async ({ page, api }) => {
  loginVerified(api)
  const detail5: ConversationDetail = { ...fakeConversationDetail, id: 5, partner: { id: 8, nickname: '다른닉' } }
  api.override({ 'GET /conversations/5': { status: 200, body: detail5 }, 'PUT /conversations/5/read': { status: 204 } })
  const calls = recordReports(page)

  await page.goto('/messages/3')
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
  await reportButton(page).click() // 양식을 열어 둔 채로 이동한다
  await expect(page.getByRole('button', { name: '신고 보내기' })).toBeVisible()
  await page.evaluate("window.history.pushState({}, '', '/messages/5'); window.dispatchEvent(new PopStateEvent('popstate'))")
  await expect(page.getByRole('heading', { name: '다른닉' })).toBeVisible()
  // 새 대화에는 열린 양식이 남아 있으면 안 된다 (닫힌 [신고] 버튼만)
  await expect(page.getByRole('button', { name: '신고 보내기' })).toHaveCount(0)
  await expect(reportButton(page)).toBeVisible()

  await page.goBack()
  await expect(page).toHaveURL(/\/messages\/3$/)
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
  await sendReport(page)
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  expect(calls.map((c) => c.path)).toEqual(['/api/conversations/3/reports'])
})

test('대화 5로 건너뛴 뒤 보낸 신고는 5번 주소로 간다', async ({ page, api }) => {
  loginVerified(api)
  const detail5: ConversationDetail = { ...fakeConversationDetail, id: 5, partner: { id: 8, nickname: '다른닉' } }
  api.override({
    'GET /conversations/5': { status: 200, body: detail5 },
    'PUT /conversations/5/read': { status: 204 },
    'POST /conversations/5/reports': { status: 201, body: { id: 10 } },
  })
  const calls = recordReports(page)
  await page.goto('/messages/3')
  await expect(page.getByRole('heading', { name: '판매자닉' })).toBeVisible()
  await page.evaluate("window.history.pushState({}, '', '/messages/5'); window.dispatchEvent(new PopStateEvent('popstate'))")
  await expect(page.getByRole('heading', { name: '다른닉' })).toBeVisible()
  await sendReport(page)
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  expect(calls.map((c) => c.path)).toEqual(['/api/conversations/5/reports'])
})

test('신고해도 읽음 처리 PUT은 다시 가지 않고 상단바 숫자는 읽음 처리 뒤 그대로다', async ({ page, api }) => {
  loginVerified(api)
  // 대화를 열어 읽음 처리가 끝나면 안 읽은 수가 0이 되는 흐름
  let unread = 2
  api.override({
    'GET /conversations/unread-count': () => ({ status: 200, body: { count: unread } }),
    'PUT /conversations/3/read': () => {
      unread = 0
      return { status: 204 }
    },
  })
  const puts = count(page, 'PUT', '/api/conversations/3/read')
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  await expect.poll(() => puts.length).toBeGreaterThan(0)
  await api.waitIdle()
  await expect(page.getByRole('link', { name: '쪽지', exact: true })).toBeVisible() // 읽음 처리 뒤라 숫자가 없다
  const before = puts.length
  await sendReport(page)
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  await api.waitIdle()
  expect(puts.length).toBe(before)
  await expect(page.getByRole('link', { name: '쪽지', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: /쪽지 \(/ })).toHaveCount(0)
})

// ---- tester 추가: 빈틈 메우기 ----

test('신고 전송 중에는 [보내기]가 잠기고 연타해도 요청은 1번만 나간다', async ({ page, api }) => {
  loginVerified(api)
  const calls = recordReports(page)
  // 응답을 0.6초 늦춰 "전송 중" 상태를 만든다 (나중에 건 route가 먼저 쓰인다)
  await page.route('**/api/conversations/3/reports', async (route) => {
    await new Promise((r) => setTimeout(r, 600))
    await route.fulfill({ status: 201, json: { id: 9 } })
  })
  await page.goto('/messages/3')
  await reportButton(page).click()
  await page.getByLabel('신고 사유').selectOption('SPAM')
  const send = page.getByRole('button', { name: '신고 보내기' })
  await send.dblclick()
  await expect(page.getByRole('button', { name: '보내는 중...' })).toBeDisabled()
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  await api.waitIdle()
  expect(calls).toHaveLength(1)
})

test('신고가 500이면 기본 실패 문구가 보이고 양식은 열린 채라 다시 보낼 수 있다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'POST /conversations/3/reports': { status: 500 } })
  await page.goto('/messages/3')
  await sendReport(page)
  await expect(page.getByRole('alert')).toHaveText('신고하지 못했습니다')
  await expect(page.getByRole('button', { name: '신고 보내기' })).toBeEnabled()
  await expect(page.locator('.msg')).toHaveCount(2)
  // 서버가 살아나면 같은 양식으로 다시 보낼 수 있다
  api.override({ 'POST /conversations/3/reports': { status: 201, body: { id: 9 } } })
  await page.getByRole('button', { name: '신고 보내기' }).click()
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
})

test('신고 중 연결이 끊기면 "서버에 연결할 수 없습니다"가 보이고 양식이 남는다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'POST /conversations/3/reports': { abort: true } })
  await page.goto('/messages/3')
  await sendReport(page)
  await expect(page.getByRole('alert')).toHaveText('서버에 연결할 수 없습니다')
  await expect(page.getByRole('button', { name: '신고 보내기' })).toBeEnabled()
})

test('사유 목록을 못 받으면 안내와 [다시 시도]가 보이고, 다시 시도하면 사유를 고를 수 있다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /report-reasons': { status: 500 } })
  await page.goto('/messages/3')
  await reportButton(page).click()
  await expect(page.getByRole('alert')).toContainText('신고 사유를 불러오지 못했습니다')
  api.override({ 'GET /report-reasons': { status: 200, body: [{ code: 'SPAM', name: '스팸' }] } })
  await page.getByRole('button', { name: '다시 시도' }).click()
  await expect(page.getByLabel('신고 사유')).toBeVisible()
})

test('키보드로 [신고]를 열고 [취소]로 닫으면 다시 [신고] 버튼이 나온다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/messages/3')
  await reportButton(page).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByLabel('신고 사유')).toBeVisible()
  await page.getByRole('button', { name: '취소' }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByLabel('신고 사유')).toHaveCount(0)
  await expect(reportButton(page)).toBeVisible()
})

test('신고 양식을 연 채로 답장을 보내도 서로 방해하지 않는다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/messages/3')
  await expect(page.locator('.msg')).toHaveCount(2)
  await reportButton(page).click()
  await page.getByLabel('신고 사유').selectOption('SPAM')
  await page.getByLabel('답장 내용').fill('답장입니다')
  await page.getByRole('button', { name: '답장 보내기' }).click()
  await expect(page.locator('.msg')).toHaveCount(3)
  // 답장 뒤에도 신고 양식과 고른 사유가 그대로고, 이어서 신고도 된다
  await expect(page.getByLabel('신고 사유')).toHaveValue('SPAM')
  await page.getByRole('button', { name: '신고 보내기' }).click()
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
  await expect(page.locator('.msg')).toHaveCount(3)
})

test('로그아웃 상태로 대화 화면에 들어가면 로그인 화면으로 가고 [신고] 버튼과 신고 요청이 없다', async ({ page, api }) => {
  const calls = recordReports(page)
  await page.goto('/messages/3')
  await expect(page).toHaveURL(/\/login/)
  await expect(reportButton(page)).toHaveCount(0)
  await api.waitIdle()
  expect(calls).toHaveLength(0)
})

test('대화 응답이 오기 전에는 [신고] 버튼이 없고 응답이 오면 나타난다', async ({ page, api }) => {
  loginVerified(api)
  await page.route('**/api/conversations/3', async (route) => {
    await new Promise((r) => setTimeout(r, 600))
    await route.fulfill({ status: 200, json: fakeConversationDetail })
  })
  await page.goto('/messages/3')
  // 대화가 아직 안 그려진 상태(불러오는 중)를 먼저 확인해야 "버튼 없음"이 의미가 있다
  await expect(page.getByText('불러오는 중')).toBeVisible()
  await expect(page.locator('.msg')).toHaveCount(0)
  await expect(reportButton(page)).toHaveCount(0)
  await expect(reportButton(page)).toBeVisible()
  await expect(page.locator('.msg')).toHaveCount(2)
})

test('대화 불러오기가 404면 [신고] 버튼이 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /conversations/3': { status: 404 } })
  await page.goto('/messages/3')
  await expect(page.getByText('대화를 찾을 수 없습니다')).toBeVisible()
  await expect(reportButton(page)).toHaveCount(0)
})

test('401로 로그인이 풀린 뒤 서버가 정상이 돼 새로고침하면 안내가 사라지고 신고가 된다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'POST /conversations/3/reports': { status: 401 }, 'POST /auth/refresh': { status: 401 } })
  await page.goto('/messages/3')
  await sendReport(page)
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  // 서버가 다시 정상이 된 상황: 신고가 받아지게 바꾸고 새로고침한다 (로그인 상태는 mockApi의 loginVerified가 유지)
  api.override({ 'POST /conversations/3/reports': { status: 201, body: { id: 9 } } })
  await page.reload()
  await expect(page.getByText(RELOGIN)).toHaveCount(0)
  await sendReport(page)
  await expect(page.getByText('신고가 접수됐습니다')).toBeVisible()
})

test('읽음 PUT 401로 로그인이 풀려도 쓰던 답장이 남는다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'PUT /conversations/3/read': { status: 401 }, 'POST /auth/refresh': { status: 401 } })
  const puts = count(page, 'PUT', '/api/conversations/3/read')
  await page.goto('/messages/3')
  await expect(page.getByLabel('답장 내용')).toBeVisible()
  await page.getByLabel('답장 내용').fill('쓰던 답장')
  // 읽음 PUT이 실제로 나가 401을 받은 뒤에도 답장 칸이 미인증 안내로 바뀌지 않는다
  await expect.poll(() => puts.length).toBeGreaterThan(0)
  await api.waitIdle()
  await expect(page.getByLabel('답장 내용')).toHaveValue('쓰던 답장')
  await expect(page.getByText('학교 인증 후 답장할 수 있어요')).toHaveCount(0)
})

test('대화 화면 [신고] 버튼은 쪽지 목록 위(머리 아래)에 있다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/messages/3')
  const btn = await reportButton(page).boundingBox()
  const list = await page.getByRole('list', { name: '쪽지 목록' }).boundingBox()
  expect(btn).not.toBeNull()
  expect(list).not.toBeNull()
  expect(btn!.y).toBeLessThan(list!.y)
})
