import { test, expect, fakeUser, loginVerified } from './mockApi.ts'
import type { Page } from '@playwright/test'
import type { VisaPlan } from '../src/types.ts'

// S12-d: 로그인한 화면 맨 위 비자 일정 띠. 서버 응답은 mockApi 가짜 응답이다.
// 띠 문구는 src에서 import하지 않고 문자열로 고정한다 (문구가 바뀌면 여기서 알 수 있게)

// 브라우저 시계를 2026-11-10(서울 낮)으로 고정한다 (/visa-plan 날짜 칸 min=오늘 때문에)
test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-11-10T03:00:00Z'))
})

// 화면이 보낸 GET /api/users/me/visa-plan 횟수를 센다 (보관소가 한 번만 부르는지 확인)
function countPlanGets(page: Page) {
  let n = 0
  page.on('request', (r) => {
    if (r.method() === 'GET' && new URL(r.url()).pathname === '/api/users/me/visa-plan') n++
  })
  return () => n
}

// 단계별 일정 (할 일 목록은 띠와 상관없어 비워 둔다)
function planOf(expiryDate: string, daysLeft: number, stage: VisaPlan['stage']): VisaPlan {
  return { expiryDate, daysLeft, stage, todos: [] }
}
const checkPlan = planOf('2026-12-08', 28, 'CHECK')
const urgentPlan = planOf('2026-12-08', 5, 'URGENT')
const preparePlan = planOf('2027-03-01', 111, 'PREPARE')
const expiredPlan = planOf('2026-11-07', -3, 'EXPIRED')
const farPlan = planOf('2027-05-30', 201, null) // 121일 이상: 단계 없음

const CHECK_TEXT = '체류 만료 D-28 — 신청 방법을 확인하세요'
// 띠 = role=status 중 "비자 일정 보기" 링크가 든 것 (다른 안내(status)와 섞이지 않게)
const banner = (page: Page) => page.getByRole('status').filter({ has: page.getByRole('link', { name: '비자 일정 보기' }) })

// ---- 언제 보이나 ----

test('30일 이내 일정이면 글 목록 위에 띠가 보이고 링크는 /visa-plan이다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  await page.goto('/')
  await expect(banner(page)).toHaveCount(1)
  await expect(banner(page).locator('.visa-banner-text')).toHaveText(CHECK_TEXT)
  await expect(banner(page).getByRole('link', { name: '비자 일정 보기' })).toHaveAttribute('href', '/visa-plan')
  // 띠는 에러가 아니므로 alert가 없어야 한다
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('단계별 문구가 맞다 (120일 이내·7일 이내·지남)', async ({ page, api }) => {
  loginVerified(api)
  const cases: [VisaPlan, string][] = [
    [preparePlan, '체류 만료 D-111 — 연장·자격 변경 준비를 확인하세요'],
    [urgentPlan, '체류 만료 D-5 — 체류 상태를 확인하세요'],
    [expiredPlan, '등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요'],
  ]
  for (const [p, text] of cases) {
    api.override({ 'GET /users/me/visa-plan': { status: 200, body: p } })
    await page.goto('/') // 새로 열면 보관소가 다시 부른다
    await expect(banner(page).locator('.visa-banner-text')).toHaveText(text)
  }
})

test('121일 이상 남아 단계가 없으면 띠가 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: farPlan } })
  await page.goto('/')
  await expect(page.getByRole('link', { name: '내 정보' })).toBeVisible()
  await api.waitIdle()
  await expect(banner(page)).toHaveCount(0)
})

test('일정이 없으면 띠가 없다', async ({ page, api }) => {
  loginVerified(api) // 기본 응답 = 일정 없음
  await page.goto('/')
  await expect(page.getByRole('link', { name: '내 정보' })).toBeVisible()
  await api.waitIdle()
  await expect(banner(page)).toHaveCount(0)
})

test('일정 불러오기가 500이면 띠도 에러 문구도 없다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 500 } })
  await page.goto('/')
  await expect(page.getByText('두 번째 가짜 글')).toBeVisible()
  await api.waitIdle()
  await expect(banner(page)).toHaveCount(0)
  await expect(page.getByRole('alert')).toHaveCount(0)
})

test('미인증 회원은 띠가 없고 일정을 부르지 않는다', async ({ page, api }) => {
  api.setLoggedIn(true)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  const gets = countPlanGets(page)
  await page.goto('/')
  await expect(page.getByRole('link', { name: '내 정보' })).toBeVisible()
  await api.waitIdle()
  await expect(banner(page)).toHaveCount(0)
  expect(gets()).toBe(0)
})

test('비로그인이면 띠가 없고 일정을 부르지 않는다', async ({ page, api }) => {
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  const gets = countPlanGets(page)
  await page.goto('/')
  await expect(page.getByRole('link', { name: '로그인' })).toBeVisible()
  await api.waitIdle()
  await expect(banner(page)).toHaveCount(0)
  expect(gets()).toBe(0)
})

// ---- 어디서 보이나 + 몇 번 부르나 ----

test('/visa-plan에서는 띠가 숨고, 화면을 옮겨도 일정은 한 번만 부른다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  const gets = countPlanGets(page)
  await page.goto('/')
  await expect(banner(page)).toHaveCount(1)
  // 띠 링크로 일정 화면 → 띠 숨김
  await banner(page).getByRole('link', { name: '비자 일정 보기' }).click()
  await expect(page).toHaveURL(/\/visa-plan$/)
  await expect(page.locator('.visa-dday')).toHaveText('D-28')
  await expect(banner(page)).toHaveCount(0)
  // 상단바로 글 목록에 돌아오면 다시 보임
  await page.getByRole('link', { name: '게시판' }).click()
  await expect(banner(page)).toHaveCount(1)
  // 화면 이동(새로고침 없이)으로는 다시 부르지 않는다
  await api.waitIdle()
  expect(gets()).toBe(1)
})

test('글 상세 화면에도 띠가 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  await page.goto('/posts/1')
  await expect(page.getByRole('heading', { name: '첫 번째 가짜 글' })).toBeVisible()
  await expect(banner(page).locator('.visa-banner-text')).toHaveText(CHECK_TEXT)
})

// ---- 닫기 ----

test('X로 닫으면 띠가 사라지고 새로고침해도 같은 단계에서는 안 뜬다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  await page.goto('/')
  await banner(page).getByRole('button', { name: '띠 닫기' }).click()
  await expect(banner(page)).toHaveCount(0)
  await page.reload()
  await expect(page.getByText('두 번째 가짜 글')).toBeVisible()
  await api.waitIdle()
  await expect(banner(page)).toHaveCount(0)
})

test('닫은 뒤 단계가 바뀌면(30일 → 7일 이내) 띠가 다시 뜬다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  await page.goto('/')
  await banner(page).getByRole('button', { name: '띠 닫기' }).click()
  await expect(banner(page)).toHaveCount(0)
  // 며칠 뒤: 같은 만료일인데 7일 이내 단계
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: urgentPlan } })
  await page.reload()
  await expect(banner(page).locator('.visa-banner-text')).toHaveText('체류 만료 D-5 — 체류 상태를 확인하세요')
})

test('7일 이내 띠도 닫을 수 있다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: urgentPlan } })
  await page.goto('/')
  await banner(page).getByRole('button', { name: '띠 닫기' }).click()
  await expect(banner(page)).toHaveCount(0)
})

// ---- 일정 화면에서 바꾸면 띠가 바로 바뀐다 (보관소 공유) ----

test('일정 화면에서 저장하면 새로고침 없이 띠가 생기고, 삭제하면 사라진다', async ({ page, api }) => {
  loginVerified(api) // 처음엔 일정 없음 → 띠 없음
  api.override({ 'PUT /users/me/visa-plan': { status: 200, body: checkPlan } })
  const gets = countPlanGets(page)
  await page.goto('/visa-plan')
  await expect(page.getByText('등록된 일정 없음')).toBeVisible()
  await page.getByLabel('체류 만료일').fill('2026-12-08')
  await page.getByRole('button', { name: '저장' }).click()
  await expect(page.locator('.visa-dday')).toHaveText('D-28')

  // 글 목록으로 가면 저장한 일정으로 띠가 보인다 (다시 부르지 않고 보관소 값으로)
  await page.getByRole('link', { name: '게시판' }).click()
  await expect(banner(page).locator('.visa-banner-text')).toHaveText(CHECK_TEXT)

  // 다시 일정 화면에서 삭제 → 글 목록에 띠 없음
  await banner(page).getByRole('link', { name: '비자 일정 보기' }).click()
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()
  await expect(page.getByText('등록된 일정 없음')).toBeVisible()
  await page.getByRole('link', { name: '게시판' }).click()
  await expect(page.getByText('두 번째 가짜 글')).toBeVisible()
  await expect(banner(page)).toHaveCount(0)
  await api.waitIdle()
  expect(gets()).toBe(1)
})

test('로그아웃하면 띠가 사라진다', async ({ page, api }) => {
  loginVerified(api)
  api.override({
    'GET /users/me/visa-plan': { status: 200, body: checkPlan },
    'POST /auth/logout': { status: 204 },
  })
  await page.goto('/')
  await expect(banner(page)).toHaveCount(1)
  // 로그아웃 뒤에는 /auth/me가 401이 되게 한다
  api.setLoggedIn(false)
  api.override({ 'GET /auth/me': { status: 401 } })
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('link', { name: '로그인' })).toBeVisible()
  await expect(banner(page)).toHaveCount(0)
})

// ---- S12-d review 보강: 보관소가 언제 부르는지·회원이 바뀔 때 ----

// 학교 인증한 회원 (id를 바꿔 다른 회원도 만든다)
const verified = (id = 1) => ({ ...fakeUser, id, verifiedSchool: { id: 1, name: '가짜대학교' } })

test('로그인 화면에서 로그인한 직후 띠가 뜨고 일정은 한 번만 부른다', async ({ page, api }) => {
  // 처음엔 비로그인. 로그인 응답이 인증 회원이면 그때 보관소가 일정을 부른다
  let loggedIn = false
  api.override({
    'GET /auth/me': () => (loggedIn ? { status: 200, body: verified() } : { status: 401 }),
    'POST /auth/refresh': () => (loggedIn ? { status: 200, body: verified() } : { status: 401 }),
    'POST /auth/login': () => {
      loggedIn = true
      return { status: 200, body: verified() }
    },
    'GET /users/me/visa-plan': { status: 200, body: checkPlan },
  })
  const gets = countPlanGets(page)
  await page.goto('/login')
  await page.getByLabel('아이디').fill(fakeUser.username)
  await page.getByLabel('비밀번호').fill('아무비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()
  await expect(banner(page).locator('.visa-banner-text')).toHaveText(CHECK_TEXT)
  await api.waitIdle()
  expect(gets()).toBe(1)
})

test('내 정보에서 학교 인증을 마치면 그때 일정을 불러 띠가 뜬다', async ({ page, api }) => {
  api.setLoggedIn(true) // 미인증 회원
  api.override({
    'POST /users/me/school-verification': { status: 200, body: verified() },
    'GET /users/me/visa-plan': { status: 200, body: checkPlan },
  })
  const gets = countPlanGets(page)
  await page.goto('/me')
  await page.getByRole('button', { name: '이미 코드를 받았어요' }).click()
  await page.getByLabel('인증 코드 (6자리)').fill('123456')
  // 인증 전에는 일정을 부르지 않는다
  await api.waitIdle()
  expect(gets()).toBe(0)
  await page.getByRole('button', { name: '확인', exact: true }).click()
  await expect(banner(page).locator('.visa-banner-text')).toHaveText(CHECK_TEXT)
  expect(gets()).toBe(1)
})

test('닫은 뒤 같은 단계라도 만료일이 바뀌면 띠가 다시 뜬다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  await page.goto('/')
  await banner(page).getByRole('button', { name: '띠 닫기' }).click()
  await expect(banner(page)).toHaveCount(0)
  // 다른 기기에서 만료일을 하루 늦췄다 (단계는 그대로 CHECK)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: planOf('2026-12-09', 29, 'CHECK') } })
  await page.reload()
  await expect(banner(page).locator('.visa-banner-text')).toHaveText('체류 만료 D-29 — 신청 방법을 확인하세요')
})

test('브라우저에 남는 닫기 기록에는 만료일이 보이지 않고 회원당 하나만 남는다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  await page.goto('/')
  await banner(page).getByRole('button', { name: '띠 닫기' }).click()
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: urgentPlan } })
  await page.reload()
  await banner(page).getByRole('button', { name: '띠 닫기' }).click()
  // 두 번 닫아도 기록은 하나, 키·값 어디에도 만료일(2026-12-08)이 없다
  const stored = await page.evaluate(() =>
    Object.keys(localStorage)
      .filter((k) => k.startsWith('visaBanner'))
      .map((k) => `${k}=${localStorage.getItem(k)}`),
  )
  expect(stored).toHaveLength(1)
  expect(stored[0]).not.toContain('2026')
})

test('앞 회원이 닫은 띠는 다른 회원으로 로그인하면 넘어오지 않는다', async ({ page, api }) => {
  // 회원 1이 닫고 로그아웃 → 회원 2로 로그인
  let who: number | null = 1
  api.override({
    'GET /auth/me': () => (who ? { status: 200, body: verified(who) } : { status: 401 }),
    'POST /auth/refresh': () => (who ? { status: 200, body: verified(who) } : { status: 401 }),
    'POST /auth/logout': () => {
      who = null
      return { status: 204 }
    },
    'POST /auth/login': () => {
      who = 2
      return { status: 200, body: verified(2) }
    },
    'GET /users/me/visa-plan': { status: 200, body: checkPlan },
  })
  await page.goto('/')
  await banner(page).getByRole('button', { name: '띠 닫기' }).click()
  await expect(banner(page)).toHaveCount(0)
  await page.getByRole('button', { name: '로그아웃' }).click()
  await expect(page.getByRole('link', { name: '로그인' })).toBeVisible()
  await page.goto('/login')
  await page.getByLabel('아이디').fill('other02')
  await page.getByLabel('비밀번호').fill('아무비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()
  // 같은 일정이어도 회원이 다르니 띠가 보여야 한다
  await expect(banner(page).locator('.visa-banner-text')).toHaveText(CHECK_TEXT)
})

test('처음 불러오기에 실패해도 일정 화면에 들어가면 한 번 다시 불러 폼이 보인다', async ({ page, api }) => {
  loginVerified(api)
  api.override({ 'GET /users/me/visa-plan': { status: 500 } })
  const gets = countPlanGets(page)
  await page.goto('/')
  await expect(page.getByText('두 번째 가짜 글')).toBeVisible()
  await api.waitIdle()
  await expect(banner(page)).toHaveCount(0)
  // 서버가 회복됐다 → 상단바 링크로 일정 화면(새로고침 없이)
  api.override({ 'GET /users/me/visa-plan': { status: 200, body: checkPlan } })
  await page.getByRole('link', { name: '비자 일정', exact: true }).click()
  await expect(page.locator('.visa-dday')).toHaveText('D-28')
  await expect(page.getByLabel('체류 만료일')).toHaveValue('2026-12-08')
  expect(gets()).toBe(2)
})
