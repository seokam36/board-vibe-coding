import { test, expect, loginVerified } from './mockApi.ts'
import type { Locator, Page } from '@playwright/test'
import type { VisaPlan } from '../src/types.ts'

// S12-c: /visa-plan 화면과 상단바 "비자 일정" 링크. 서버 응답은 mockApi 가짜 응답이다

// 화면이 보낸 /api/users/me/visa-plan 요청을 메서드별로 센다 (몇 번 나갔는지, 무엇을 보냈는지 확인용)
function recordPlanRequests(page: Page) {
  const calls: { method: string; body: string | null }[] = []
  page.on('request', (r) => {
    if (new URL(r.url()).pathname === '/api/users/me/visa-plan') calls.push({ method: r.method(), body: r.postData() })
  })
  return {
    count: (method: string) => calls.filter((c) => c.method === method).length,
    bodies: (method: string) => calls.filter((c) => c.method === method).map((c) => c.body),
  }
}

// 만료 111일 전 일정 (단계 PREPARE: 첫 줄이 현재 단계, 나머지 둘은 아직 안 온 단계)
const plan: VisaPlan = {
  expiryDate: '2027-03-01',
  daysLeft: 111,
  stage: 'PREPARE',
  todos: [
    { code: 'PREPARE', title: '연장 준비를 시작하세요', dueDate: '2026-11-01', daysLeft: -9 },
    { code: 'CHECK', title: '서류를 확인하세요', dueDate: '2027-01-30', daysLeft: 81 },
    { code: 'URGENT', title: '체류 상태를 확인하세요', dueDate: '2027-02-22', daysLeft: 104 },
  ],
}

// 만료 당일: 큰 글씨 D-Day, 단계 URGENT라 URGENT 줄이 현재 단계, 앞 두 줄은 지난 단계
const dDayPlan: VisaPlan = {
  expiryDate: '2026-11-10',
  daysLeft: 0,
  stage: 'URGENT',
  todos: [
    { code: 'PREPARE', title: '연장 준비를 시작하세요', dueDate: '2026-07-13', daysLeft: -120 },
    { code: 'CHECK', title: '서류를 확인하세요', dueDate: '2026-10-11', daysLeft: -30 },
    { code: 'URGENT', title: '체류 상태를 확인하세요', dueDate: '2026-11-03', daysLeft: -7 },
  ],
}

// 만료 3일 지남: 큰 글씨 D+3, 중립 안내
const expiredPlan: VisaPlan = {
  expiryDate: '2026-11-07',
  daysLeft: -3,
  stage: 'EXPIRED',
  todos: [
    { code: 'PREPARE', title: '연장 준비를 시작하세요', dueDate: '2026-07-10', daysLeft: -123 },
    { code: 'CHECK', title: '서류를 확인하세요', dueDate: '2026-10-08', daysLeft: -33 },
    { code: 'URGENT', title: '체류 상태를 확인하세요', dueDate: '2026-10-31', daysLeft: -10 },
  ],
}

// 할 일 줄의 굵은 칸은 "만료일 - dueDate"(120/30/7일 전)이라 어느 일정이든 같다
const TODO_DDAYS = ['만료 120일 전부터', '만료 30일 전부터', '만료 7일 전부터']

// 할 일 줄 안의 날짜 글자와 상태 글자를 따로 집는다 (전체 일치로 비교하려고)
const todoDate = (row: Locator) => row.locator('.meta > span').nth(0)
const todoStatus = (row: Locator) => row.locator('.meta > span').nth(1)

const EXPIRED_NOTICE = '등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요'
const RELOGIN = '다시 로그인해 주세요 로그인'

// 브라우저 시계를 2026-11-10(서울 낮)으로 고정한다.
// 날짜 칸에 min=오늘이 걸려 있어서, 실제 날짜가 2027-03-02를 넘으면 2027-03-01 입력이 브라우저에서 막혀 테스트가 깨지기 때문
test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-10T03:00:00Z'))
})

// ---- 조건 1: 상단바 링크 ----

test('상단바 "비자 일정" 링크는 미인증 회원에게 없다', async ({ page, api }) => {
  api.setLoggedIn(true) // 로그인했지만 미인증
  await page.goto('/')
  await expect(page.getByRole('link', { name: '내 정보' })).toBeVisible()
  await expect(page.getByRole('link', { name: '비자 일정' })).toHaveCount(0)
})

test('상단바 "비자 일정" 링크는 비로그인에게 없다', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('link', { name: '로그인' })).toBeVisible()
  await expect(page.getByRole('link', { name: '비자 일정' })).toHaveCount(0)
})

test('인증 회원은 상단바 "비자 일정" 링크로 /visa-plan에 간다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/')
  const link = page.getByRole('link', { name: '비자 일정' })
  await expect(link).toHaveAttribute('href', '/visa-plan')
  await link.click()
  await expect(page).toHaveURL(/\/visa-plan$/)
  await expect(page.getByRole('heading', { name: '비자 일정' })).toBeVisible()
})

// ---- 조건 4·11: 미인증·비로그인 ----

test('미인증 회원은 학교 인증 안내만 보이고 일정을 부르지 않는다', async ({ page, api }) => {
  api.setLoggedIn(true)
  const reqs = recordPlanRequests(page)
  await page.goto('/visa-plan')
  await expect(page.getByText('비자 일정은 학교 인증 후에 등록할 수 있어요')).toBeVisible()
  await expect(page.getByLabel('체류 만료일')).toHaveCount(0)
  await api.waitIdle()
  expect(reqs.count('GET')).toBe(0)
})

test('비로그인으로 /visa-plan에 들어가면 로그인 화면으로 간다', async ({ page }) => {
  await page.goto('/visa-plan')
  await expect(page).toHaveURL(/\/login/)
})

// ---- 조건 3·2: 빈 상태와 저장 ----

test('일정이 없으면 빈 안내와 입력칸이 보이고, 저장하면 PUT 1번 뒤 D-n·할 일 3줄이 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'PUT /users/me/visa-plan': { status: 200, body: plan } })
  const reqs = recordPlanRequests(page)
  await page.goto('/visa-plan')
  await expect(page.getByText('등록된 일정 없음')).toBeVisible()
  await page.getByLabel('체류 만료일').fill('2027-03-01')
  await page.getByRole('button', { name: '저장' }).click()

  await expect(page.locator('.visa-dday')).toHaveText('D-111')
  await expect(page.getByText('체류 만료일 2027년 3월 1일')).toBeVisible()
  await expect(page.locator('.visa-todo')).toHaveCount(3)
  // 옛 "지남" 표시는 없고, 현재 단계(PREPARE) 줄만 "지금 확인할 때", 나머지는 "n일 뒤 시작"
  await expect(page.getByText('지남', { exact: true })).toHaveCount(0)
  await expect(page.locator('.visa-todo-dday')).toHaveText(TODO_DDAYS)
  const rows = page.locator('.visa-todo')
  await expect(todoStatus(rows.nth(0))).toHaveText('지금 확인할 때')
  await expect(todoStatus(rows.nth(1))).toHaveText('81일 뒤 시작')
  await expect(todoStatus(rows.nth(2))).toHaveText('104일 뒤 시작')
  // 요청은 PUT 한 번, 본문은 만료일 하나
  expect(reqs.count('PUT')).toBe(1)
  expect(JSON.parse(reqs.bodies('PUT')[0] ?? '{}')).toEqual({ expiryDate: '2027-03-01' })
})

// ---- 조건 5: 저장 실패 ----

test('저장이 400이면 형식·범위 문구가 보이고 입력값은 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'PUT /users/me/visa-plan': { status: 400 } })
  await page.goto('/visa-plan')
  await page.getByLabel('체류 만료일').fill('2027-03-01')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText('만료일을 확인해 주세요 (오늘부터 5년 이내의 날짜만 등록할 수 있어요)')
  await expect(page.getByLabel('체류 만료일')).toHaveValue('2027-03-01')
})

test('저장이 403이면 학교 인증 안내 문구가 보이고 입력값은 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'PUT /users/me/visa-plan': { status: 403 } })
  await page.goto('/visa-plan')
  await page.getByLabel('체류 만료일').fill('2027-03-01')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText('학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요')
  await expect(page.getByLabel('체류 만료일')).toHaveValue('2027-03-01')
})

// ---- 조건 6: 삭제 ----

test('삭제 확인 창에서 취소하면 DELETE가 나가지 않고 일정이 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: plan } })
  const reqs = recordPlanRequests(page)
  await page.goto('/visa-plan')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '취소' }).click()
  await expect(page.locator('.visa-dday')).toHaveText('D-111')
  await api.waitIdle()
  expect(reqs.count('DELETE')).toBe(0)
})

test('삭제를 확인하면 DELETE 1번 뒤 빈 상태가 된다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: plan } })
  const reqs = recordPlanRequests(page)
  await page.goto('/visa-plan')
  await expect(page.getByLabel('체류 만료일')).toHaveValue('2027-03-01')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByText('등록된 일정 없음')).toBeVisible()
  await expect(page.getByLabel('체류 만료일')).toHaveValue('')
  expect(reqs.count('DELETE')).toBe(1)
})

test('삭제가 404면 "이미 삭제된 일정입니다"가 보이고 빈 상태가 된다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /users/me/visa-plan': { status: 200, body: plan },
    'DELETE /users/me/visa-plan': { status: 404 },
  })
  await page.goto('/visa-plan')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 일정입니다')
  await expect(page.getByText('등록된 일정 없음')).toBeVisible()
})

// ---- 조건 7: 일반 안내 ----

test('일반 안내 한 줄과 하이코리아 링크가 있다', async ({ page, api }) => {
  loginVerified(api)
  await page.goto('/visa-plan')
  await expect(page.getByText('등록된 일정 없음')).toBeVisible()
  await expect(page.getByText('일반 안내이며 개인 상황은 하이코리아나 관할 출입국·외국인청에서 확인하세요')).toBeVisible()
  await expect(page.getByRole('link', { name: '하이코리아' })).toHaveAttribute('href', 'https://www.hikorea.go.kr/')
})

// ---- 조건 8·9: D-Day·D+n·지남·만료 안내 ----

test('만료 당일이면 큰 글씨가 D-Day이고 지난 단계 2줄은 흐리게, 현재 단계(URGENT) 줄은 "지금 확인할 때"다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: dDayPlan } })
  await page.goto('/visa-plan')
  await expect(page.locator('.visa-dday')).toHaveText('D-Day')
  await expect(page.locator('.visa-todo-dday')).toHaveText(TODO_DDAYS)
  // stage가 URGENT라 URGENT 줄은 현재 단계(흐리지 않음), 앞 두 줄은 "지난 단계"(흐림)
  const rows = page.locator('.visa-todo')
  await expect(todoStatus(rows.nth(0))).toHaveText('지난 단계')
  await expect(todoStatus(rows.nth(1))).toHaveText('지난 단계')
  await expect(todoStatus(rows.nth(2))).toHaveText('지금 확인할 때')
  await expect(page.locator('.visa-todo-past')).toHaveCount(2)
  await expect(rows.nth(2)).not.toHaveClass(/visa-todo-past/)
  await expect(page.getByText('지남', { exact: true })).toHaveCount(0)
  // 아직 지나지 않았으니(0일) 만료 안내는 없다
  await expect(page.getByText(EXPIRED_NOTICE)).toHaveCount(0)
})

test('만료일이 3일 지났으면 D+3과 중립 안내가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: expiredPlan } })
  await page.goto('/visa-plan')
  await expect(page.locator('.visa-dday')).toHaveText('D+3')
  await expect(page.getByText(EXPIRED_NOTICE)).toBeVisible()
  // 만료 뒤에는 할 일 줄이 전부 "지난 단계"+흐림 (현재 단계 없음)
  await expect(page.locator('.visa-todo-dday')).toHaveText(TODO_DDAYS)
  const rows = page.locator('.visa-todo')
  for (let i = 0; i < 3; i++) await expect(todoStatus(rows.nth(i))).toHaveText('지난 단계')
  await expect(page.getByText('지난 단계', { exact: true })).toHaveCount(3)
  await expect(page.locator('.visa-todo-past')).toHaveCount(3)
  await expect(page.getByText('지금 확인할 때')).toHaveCount(0)
})

test('만료 전 일정에는 만료 안내가 없고 할 일 줄이 "만료 N일 전부터"로 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: plan } })
  await page.goto('/visa-plan')
  await expect(page.locator('.visa-dday')).toHaveText('D-111')
  await expect(page.locator('.visa-todo-dday')).toHaveText(TODO_DDAYS)
  await expect(page.getByText(EXPIRED_NOTICE)).toHaveCount(0)
})

// ---- review 수정: 사용자가 헷갈렸던 상황(만료 D-15, 단계 CHECK)을 그대로 고정 ----

test('만료 D-15(CHECK)면 CHECK 줄이 "지금 확인할 때"(흐리지 않음), PREPARE 줄은 "지난 단계"(흐림), URGENT 줄은 "8일 뒤 시작"이다', async ({ page, api }) => {
  // 시계 2026-11-10 기준 만료 2026-11-25. 서버 규칙대로 dueDate = 만료일-120/30/7, daysLeft = dueDate - 오늘
  const d15Plan: VisaPlan = {
    expiryDate: '2026-11-25',
    daysLeft: 15,
    stage: 'CHECK',
    todos: [
      { code: 'PREPARE', title: '연장 준비를 시작하세요', dueDate: '2026-07-28', daysLeft: -105 },
      { code: 'CHECK', title: '서류를 확인하세요', dueDate: '2026-10-26', daysLeft: -15 },
      { code: 'URGENT', title: '체류 상태를 확인하세요', dueDate: '2026-11-18', daysLeft: 8 },
    ],
  }
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: d15Plan } })
  await page.goto('/visa-plan')
  await expect(page.locator('.visa-dday')).toHaveText('D-15') // 위 큰 D-n은 만료일 기준 그대로
  await expect(page.locator('.visa-todo-dday')).toHaveText(TODO_DDAYS)
  const rows = page.locator('.visa-todo')
  // PREPARE: 이미 지난 단계, 흐리게
  await expect(todoDate(rows.nth(0))).toHaveText('2026년 7월 28일')
  await expect(todoStatus(rows.nth(0))).toHaveText('지난 단계')
  await expect(rows.nth(0)).toHaveClass(/visa-todo-past/)
  // CHECK: 지금 들어와 있는 단계. 날짜는 지났어도(-15) 흐리지 않고 "지금 확인할 때"
  await expect(todoDate(rows.nth(1))).toHaveText('2026년 10월 26일')
  await expect(todoStatus(rows.nth(1))).toHaveText('지금 확인할 때')
  await expect(todoStatus(rows.nth(1))).toHaveClass(/visa-todo-status/) // 청자색 강조는 이 글자에만
  await expect(rows.nth(1)).not.toHaveClass(/visa-todo-past/)
  await expect(rows.nth(1)).not.toContainText('지난 단계')
  // URGENT: 아직 안 온 단계
  await expect(todoDate(rows.nth(2))).toHaveText('2026년 11월 18일')
  await expect(todoStatus(rows.nth(2))).toHaveText('8일 뒤 시작')
  await expect(rows.nth(2)).not.toHaveClass(/visa-todo-past/)
  // 흐린 줄은 PREPARE 하나뿐
  await expect(page.locator('.visa-todo-past')).toHaveCount(1)
})

// ---- 조건 10: 불러오기 실패 ----

test('불러오기가 500이면 "일정을 불러오지 못했습니다"가 보이고 폼은 숨는다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 500 } })
  await page.goto('/visa-plan')
  await expect(page.getByRole('alert')).toHaveText('일정을 불러오지 못했습니다')
  await expect(page.getByLabel('체류 만료일')).toHaveCount(0)
})

// ---- review 보강: 남는 에러 문구·로그인 풀림·빠진 장면 ----

test('삭제 404 문구는 새 날짜를 저장하면 사라진다', async ({ page, api }) => {
  // review [필수]: 이전 삭제 실패 문구가 저장 성공 뒤에도 남아 있던 문제
  loginVerified(api)
  api.override({
    'GET /users/me/visa-plan': { status: 200, body: plan },
    'DELETE /users/me/visa-plan': { status: 404 },
    'PUT /users/me/visa-plan': { status: 200, body: plan },
  })
  await page.goto('/visa-plan')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 일정입니다')

  await page.getByLabel('체류 만료일').fill('2027-03-01')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.locator('.visa-dday')).toHaveText('D-111')
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('저장 중 로그인이 풀리면 다시 로그인 안내가 보이고 입력값과 주소는 그대로다', async ({ page, api }) => {
  // PUT 401 → 앱이 재발급 시도 → 재발급도 401 = 로그인 풀림
  loginVerified(api)
  api.override({
    'PUT /users/me/visa-plan': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/visa-plan')
  await page.getByLabel('체류 만료일').fill('2027-03-01')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  await expect(page.getByLabel('체류 만료일')).toHaveValue('2027-03-01')
  await expect(page).toHaveURL(/\/visa-plan$/)
})

test('불러오기 중 로그인이 풀리면 다시 로그인 안내만 보이고 폼은 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /users/me/visa-plan': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/visa-plan')
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  await expect(page.getByLabel('체류 만료일')).toHaveCount(0)
})

test('날짜를 비우고 저장하면 "만료일을 입력해 주세요"가 보이고 PUT은 나가지 않는다', async ({ page, api }) => {
  loginVerified(api)
  const reqs = recordPlanRequests(page)
  await page.goto('/visa-plan')
  await expect(page.getByText('등록된 일정 없음')).toBeVisible()
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText('만료일을 입력해 주세요')
  await api.waitIdle()
  expect(reqs.count('PUT')).toBe(0)
})

test('삭제가 500이면 "삭제하지 못했습니다"가 보이고 일정은 그대로다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /users/me/visa-plan': { status: 200, body: plan },
    'DELETE /users/me/visa-plan': { status: 500 },
  })
  await page.goto('/visa-plan')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByRole('alert')).toHaveText('삭제하지 못했습니다')
  await expect(page.locator('.visa-dday')).toHaveText('D-111')
})

test('일정이 있으면 버튼이 "만료일 변경"이고 바꾼 날짜로 PUT이 나간다', async ({ page, api }) => {
  // 새 날짜 2027-03-31 = 2026-11-10로부터 141일 → 단계 없음(null)
  const later: VisaPlan = { ...plan, expiryDate: '2027-03-31', daysLeft: 141, stage: null }
  loginVerified(api)
  api.override({
    'GET /users/me/visa-plan': { status: 200, body: plan },
    'PUT /users/me/visa-plan': { status: 200, body: later },
  })
  const reqs = recordPlanRequests(page)
  await page.goto('/visa-plan')
  await expect(page.getByRole('button', { name: '저장', exact: true })).toHaveCount(0)
  await page.getByLabel('체류 만료일').fill('2027-03-31')
  await page.getByRole('button', { name: '만료일 변경' }).click()
  await expect(page.locator('.visa-dday')).toHaveText('D-141')
  expect(JSON.parse(reqs.bodies('PUT')[0] ?? '{}')).toEqual({ expiryDate: '2027-03-31' })
})

test('121일 이상 남아 단계가 없어도 D-n과 할 일이 보이고 만료 안내는 없다', async ({ page, api }) => {
  const farPlan: VisaPlan = {
    expiryDate: '2027-05-30',
    daysLeft: 201,
    stage: null,
    todos: [
      { code: 'PREPARE', title: '연장 준비를 시작하세요', dueDate: '2027-01-30', daysLeft: 81 },
      { code: 'CHECK', title: '서류를 확인하세요', dueDate: '2027-04-30', daysLeft: 171 },
      { code: 'URGENT', title: '체류 상태를 확인하세요', dueDate: '2027-05-23', daysLeft: 194 },
    ],
  }
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: farPlan } })
  await page.goto('/visa-plan')
  await expect(page.locator('.visa-dday')).toHaveText('D-201')
  await expect(page.locator('.visa-todo-dday')).toHaveText(TODO_DDAYS)
  const rows = page.locator('.visa-todo')
  await expect(todoStatus(rows.nth(0))).toHaveText('81일 뒤 시작')
  await expect(todoStatus(rows.nth(1))).toHaveText('171일 뒤 시작')
  await expect(todoStatus(rows.nth(2))).toHaveText('194일 뒤 시작')
  await expect(page.getByText('지난 단계')).toHaveCount(0)
  await expect(page.getByText('지금 확인할 때')).toHaveCount(0)
  await expect(page.locator('.visa-todo-past')).toHaveCount(0)
  await expect(page.getByText('지남', { exact: true })).toHaveCount(0)
  await expect(page.getByText(EXPIRED_NOTICE)).toHaveCount(0)
})

test('미인증 화면에도 일반 안내와 하이코리아 링크가 있다', async ({ page, api }) => {
  api.setLoggedIn(true)
  await page.goto('/visa-plan')
  await expect(page.getByText('비자 일정은 학교 인증 후에 등록할 수 있어요')).toBeVisible()
  await expect(page.getByText('일반 안내이며 개인 상황은 하이코리아나 관할 출입국·외국인청에서 확인하세요')).toBeVisible()
  await expect(page.getByRole('link', { name: '하이코리아' })).toHaveAttribute('href', 'https://www.hikorea.go.kr/')
})

// ---- S12-d review 보강: 보관소로 바뀐 뒤의 페이지 동작 ----

test('일정 화면에 바로 들어와 불러오기가 실패하면 다시 부르지 않는다 (GET 1번)', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 500 } })
  const reqs = recordPlanRequests(page)
  await page.goto('/visa-plan')
  await expect(page.getByRole('alert')).toHaveText('일정을 불러오지 못했습니다')
  await api.waitIdle()
  expect(reqs.count('GET')).toBe(1)
})

test('일정이 있을 때 저장 중 로그인이 풀려도 D-n과 폼이 남는다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /users/me/visa-plan': { status: 200, body: plan },
    'PUT /users/me/visa-plan': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/visa-plan')
  await page.getByLabel('체류 만료일').fill('2027-03-31')
  await page.getByRole('button', { name: '만료일 변경' }).click()
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  await expect(page.locator('.visa-dday')).toHaveText('D-111')
  await expect(page.getByLabel('체류 만료일')).toHaveValue('2027-03-31')
})

test('로그인이 풀린 뒤 다른 탭에서 다시 로그인하고 저장하면 화면이 새 일정으로 바뀐다', async ({ page, api }) => {
  // review [필수]: 풀린 화면에서 다시 저장이 성공해도 옛 화면과 재로그인 안내가 남던 문제
  loginVerified(api)
  api.override({
    'PUT /users/me/visa-plan': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/visa-plan')
  await page.getByLabel('체류 만료일').fill('2027-03-01')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.getByRole('alert')).toHaveText(RELOGIN)
  // 다른 탭에서 다시 로그인해 쿠키가 살아났다 → 이 탭에서 다시 저장하면 성공
  api.override({ 'PUT /users/me/visa-plan': { status: 200, body: plan } })
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.locator('.visa-dday')).toHaveText('D-111')
  await expect(page.getByRole('alert')).toHaveCount(0)
})
