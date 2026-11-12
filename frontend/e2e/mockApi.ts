import { test as base, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import type { AuthUser } from '../src/auth/useAuth.ts'
import type { Category, Comment, MarketItemPhoto, MarketListItem, Post, School, VisaPlan, VisaReview, VisaReviewOptions, VisaReviewStat } from '../src/types.ts'

// ---- 가짜 데이터: src/types.ts 타입을 붙여서 실제 응답 모양과 어긋나면 tsc가 알려 준다 ----

export const fakeUser: AuthUser = {
  id: 1,
  username: 'tester01',
  nickname: '테스터',
  verifiedSchool: null,
  role: 'USER',
}

// docs/api.md 기준: GET /schools 에는 emailDomains가 있고, 글 응답의 school에는 id·name만 있다
const fakeSchools: School[] = [{ id: 1, name: '가짜대학교', emailDomains: ['fake.ac.kr'] }]
const postSchool: School = { id: 1, name: '가짜대학교' }
// 말머리 코드는 실제와 같게 CHAT·QUESTION·CAMPUS_LIFE
const fakeCategories: Category[] = [
  { code: 'CHAT', name: '잡담' },
  { code: 'QUESTION', name: '질문' },
  { code: 'CAMPUS_LIFE', name: '학교생활' },
]

// 목록은 최신순(id 내림차순)이라 id 2가 먼저다
export const fakePosts: Post[] = [
  {
    id: 2,
    title: '두 번째 가짜 글',
    content: '두 번째 글의 내용입니다.',
    author: { id: 3, nickname: '글쓴이B' },
    school: postSchool,
    category: fakeCategories[1],
    createdAt: '2026-01-02T00:00:00Z',
    commentCount: 0,
    likeCount: 0,
  },
  {
    id: 1,
    title: '첫 번째 가짜 글',
    content: '첫 번째 글의 내용입니다.',
    author: { id: 2, nickname: '글쓴이A' },
    school: postSchool,
    category: fakeCategories[0],
    createdAt: '2026-01-01T00:00:00Z',
    commentCount: 1,
    likeCount: 3,
  },
]

const fakeComments: Comment[] = [
  { id: 1, postId: 1, content: '첫 댓글', createdAt: '2026-01-01T01:00:00Z', author: { id: 3, nickname: '글쓴이B' } },
]

// ---- 비자 화면 장면용 기본 가짜 데이터 (선택지·후기·통계) ----
// docs/api.md 17번: 비자 후기 선택지 (고정값)
const fakeVisaOptions: VisaReviewOptions = {
  visaTypes: [{ code: 'D_2', name: 'D-2 유학' }],
  applicationTypes: [{ code: 'EXTENSION', name: '체류기간 연장' }],
  results: [{ code: 'APPROVED', name: '승인' }],
}
// docs/api.md 20번: 비자 후기 목록 (후기 한 건)
const fakeVisaReviews: VisaReview[] = [
  {
    id: 1,
    author: { id: 2, nickname: '글쓴이A' },
    school: postSchool,
    visaType: fakeVisaOptions.visaTypes[0],
    applicationType: fakeVisaOptions.applicationTypes[0],
    receivedDate: '2026-01-01',
    resultDate: '2026-01-11',
    result: fakeVisaOptions.results[0],
    processingDays: 10,
    content: '후기 내용',
    createdAt: '2026-01-12T00:00:00Z',
  },
]
// docs/api.md 23번: 비자 후기 통계
const fakeVisaStats: VisaReviewStat[] = [
  {
    school: postSchool,
    visaType: fakeVisaOptions.visaTypes[0],
    applicationType: fakeVisaOptions.applicationTypes[0],
    count: 1,
    avgDays: 10,
  },
]

// docs/api.md 24번: 내 비자 일정. 기본은 일정 없음(200 + null들)
const fakeEmptyVisaPlan: VisaPlan = { expiryDate: null, daysLeft: null, stage: null, todos: [] }

// ---- 가짜 응답을 만드는 도우미 ----

// 한 요청에 대한 가짜 응답. status는 상태코드, body는 JSON으로 내려갈 값(없으면 본문 없이 보낸다)
// { abort: true }는 응답 없이 연결이 끊긴 것처럼 만든다 (서버 꺼짐 같은 네트워크 오류 장면용)
export type MockResponse = { status: number; body?: unknown } | { abort: true; status?: never }
// 키는 "메서드 경로"(예: "GET /posts"). 경로는 /api를 뗀 것이고 ?뒤 쿼리는 뺀다
export type MockHandlers = Record<string, MockResponse | (() => MockResponse)>

// 1x1 투명 PNG 바이트. 사진 주소의 기본 응답으로 쓴다
const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
)

// 중고 글 목록 항목의 기본 가짜 데이터 (docs/api.md 29번). 대표 사진이 있는 글 하나.
// 목록 화면 장면(tester)이 override({ 'GET /market-items': ... })로 갈아 끼워 쓴다
export const fakeMarketListItem: MarketListItem = {
  id: 1,
  title: '가짜 자전거',
  content: '거의 새 자전거입니다.',
  tradeType: { code: 'SALE', name: '판매' },
  price: 5000,
  status: { code: 'ON_SALE', name: '판매중' },
  school: postSchool,
  seller: { id: 1, nickname: '테스터' },
  createdAt: '2026-01-02T00:00:00Z',
  coverPhoto: { id: 7, width: 1365, height: 2048, thumbWidth: 213, thumbHeight: 320, contentType: 'image/jpeg' },
}

// 기본 가짜 응답 모음. 로그인 여부는 state.loggedIn 으로 정한다
function defaultHandlers(state: { loggedIn: boolean }): MockHandlers {
  let nextPhotoId = 100 // 사진 올리기 가짜 응답의 id (테스트마다 새로 시작)
  return {
    // docs/api.md: GET /csrf 는 헤더 이름을 돌려준다 (쿠키 설정은 서버가 하니 여기선 본문만)
    'GET /csrf': { status: 200, body: { headerName: 'X-XSRF-TOKEN' } },
    // 로그인 전에는 401, 로그인 뒤에는 내 정보
    'GET /auth/me': () => (state.loggedIn ? { status: 200, body: fakeUser } : { status: 401 }),
    // 앱은 me가 401이면 refresh를 시도한다. 비로그인이니 이것도 401
    'POST /auth/refresh': () => (state.loggedIn ? { status: 200, body: fakeUser } : { status: 401 }),
    'POST /auth/login': () => {
      state.loggedIn = true
      return { status: 200, body: fakeUser }
    },
    'GET /schools': { status: 200, body: fakeSchools },
    'GET /categories': { status: 200, body: fakeCategories },
    'GET /posts': { status: 200, body: fakePosts },
    'GET /posts/1': { status: 200, body: fakePosts[1] },
    'GET /posts/1/comments': { status: 200, body: fakeComments },
    // 로그인한 사람이 글 상세를 열면 "내가 공감했는지"를 확인한다 (docs/api.md 5-9)
    'GET /posts/1/likes/me': { status: 200, body: { liked: false } },
    // 비자 후기 화면: 선택지·목록·통계 (docs/api.md 17·20·23번)
    'GET /visa-review-options': { status: 200, body: fakeVisaOptions },
    'GET /visa-reviews': { status: 200, body: fakeVisaReviews },
    'GET /visa-reviews/stats': { status: 200, body: fakeVisaStats },
    // 내 비자 일정 (docs/api.md 24~26번): 조회·저장은 일정 없음, 삭제는 204
    'GET /users/me/visa-plan': { status: 200, body: fakeEmptyVisaPlan },
    'PUT /users/me/visa-plan': { status: 200, body: fakeEmptyVisaPlan },
    'DELETE /users/me/visa-plan': { status: 204 },
    // 사진 올리기(33번)·지우기(35번). 올릴 때마다 사진 id가 1씩 늘어난다 (100부터: 글에 이미 있는 사진 id와 안 겹치게)
    'POST /market-items/*/photos': () => ({
      status: 201,
      body: { id: nextPhotoId++, width: 1365, height: 2048, thumbWidth: 213, thumbHeight: 320, contentType: 'image/jpeg' } satisfies MarketItemPhoto,
    }),
    'DELETE /market-items/*/photos/*': { status: 204 },
  }
}

// 키에 "*"가 있으면 그 자리 한 구간(슬래시 없는 글자)과 맞는다. 정확한 키가 없을 때만 쓴다
function findWildcardHandler(handlers: MockHandlers, key: string) {
  for (const pattern of Object.keys(handlers)) {
    if (!pattern.includes('*')) continue
    const p = pattern.split('/')
    const k = key.split('/')
    if (p.length === k.length && p.every((seg, i) => seg === '*' || seg === k[i])) return handlers[pattern]
  }
  return undefined
}

// page.route로 /api 요청을 가로채 가짜 응답을 준다. 준비 안 된 요청은 unhandled에 기록하고 404로 막는다
export async function mockApi(page: Page, options: { loggedIn?: boolean } = {}) {
  const state = { loggedIn: options.loggedIn ?? false }
  // 요청이 올 때마다 이 객체에서 찾는다(요청 시점 조회). 그래서 나중에 override로 바꿔도 바로 반영된다
  const handlers = defaultHandlers(state)
  const unhandled: string[] = []
  let pending = 0 // 아직 끝나지 않은 /api 요청 수

  const isApi = (url: string) => new URL(url).pathname.startsWith('/api/')
  page.on('request', (r) => isApi(r.url()) && pending++)
  page.on('requestfinished', (r) => isApi(r.url()) && pending--)
  page.on('requestfailed', (r) => isApi(r.url()) && pending--)

  // 주소가 /api/ 로 시작하는 요청만 가로챈다 (/src/api.ts 같은 소스 파일 요청은 건드리지 않는다)
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const req = route.request()
      const path = new URL(req.url()).pathname.replace(/^\/api/, '')
      const key = `${req.method()} ${path}`
      // 정확한 키가 먼저, 없으면 "*"(한 구간)가 든 키로 찾는다 (예: "POST /market-items/*/photos")
      const handler = handlers[key] ?? findWildcardHandler(handlers, key)
      // 사진 파일 주소(docs/api.md 34번, ?size=thumb 포함)는 따로 준비하지 않아도 작은 PNG를 돌려준다.
      // 목록·상세 화면이 사진을 부르기 때문. 테스트가 같은 주소를 override하면 그쪽이 먼저다
      if (!handler && req.method() === 'GET' && /^\/market-items\/[^/]+\/photos\/[^/]+$/.test(path)) {
        await route.fulfill({ status: 200, contentType: 'image/png', body: TINY_PNG })
        return
      }
      if (!handler) {
        unhandled.push(key)
        await route.fulfill({ status: 404, json: { message: `mock 없음: ${key}` } })
        return
      }
      const res = typeof handler === 'function' ? handler() : handler
      // 연결 끊김: 화면은 response 없는 에러를 받는다
      if ('abort' in res) {
        await route.abort('failed')
        return
      }
      // body가 없으면 본문 없이 보낸다 (204 같은 응답에 본문이 붙지 않게). body가 있으면 JSON으로 보낸다
      if (res.body === undefined) await route.fulfill({ status: res.status })
      else await route.fulfill({ status: res.status, json: res.body })
    },
  )
  return {
    unhandled,
    // 일부 주소의 가짜 응답을 갈아 끼운다 (예: override({ 'GET /posts': { status: 500 } }))
    override(next: MockHandlers) {
      Object.assign(handlers, next)
    },
    // 로그인 상태를 바꾼다 (페이지를 열기 전에 호출)
    setLoggedIn(value: boolean) {
      state.loggedIn = value
    },
    // 진행 중인 /api 요청이 0이 될 때까지 기다린다 (늦게 온 요청이 검사에서 빠지지 않게)
    async waitIdle() {
      await expect.poll(() => pending, { intervals: [20, 50, 100], timeout: 3000 }).toBe(0)
    },
  }
}

// 테스트에서 쓰는 test: 매 테스트마다 가짜 API를 자동으로 켜고, 끝나면 "준비 안 한 요청이 없었는지" 검사한다
export const test = base.extend<{ api: Awaited<ReturnType<typeof mockApi>> }>({
  api: [
    // 인자 이름을 provide로 쓴 이유: Playwright 문서는 use라 부르지만, use는 React 19의 훅 이름이라 lint 규칙(react-hooks)이 훅 호출로 오해한다
    async ({ page }, provide) => {
      const api = await mockApi(page)
      await provide(api)
      await api.waitIdle()
      // 하나라도 있으면 실패: 화면이 예상 못 한 주소를 불렀다는 뜻
      expect(api.unhandled, '준비하지 않은 /api 요청이 있었다').toEqual([])
    },
    { auto: true },
  ],
})

// 로그인 + 학교 인증(가짜대학교)을 켠다. 여러 spec이 같이 쓴다 (페이지를 열기 전에 호출)
export function loginVerified(api: { setLoggedIn: (v: boolean) => void; override: (h: MockHandlers) => void }) {
  api.setLoggedIn(true)
  api.override({ 'GET /auth/me': { status: 200, body: { ...fakeUser, verifiedSchool: { id: 1, name: '가짜대학교' } } } })
}

export { expect }
