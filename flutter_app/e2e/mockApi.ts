import { test as base, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { deflateSync } from 'node:zlib'

// Flutter 웹 e2e용 가짜 서버(백엔드 없이 /api 응답을 흉내 낸다).
// 옛 React e2e의 mockApi.ts(S16-p에서 삭제)에서 "핵심 흐름·실패 스펙에 필요한 부분만" 옮겼다.
// 독립된 폴더라 타입도 여기서 간단히 직접 정의한다.
// 응답 모양은 docs/api.md 기준이다.

// ---- 가짜 데이터 ----

type School = { id: number; name: string }
export type FakeUser = {
  id: number
  username: string
  nickname: string
  verifiedSchool: School | null
  role: 'USER' | 'ADMIN'
}

export const fakeUser: FakeUser = { id: 1, username: 'tester01', nickname: '테스터', verifiedSchool: null, role: 'USER' }
// 학교 인증을 마친 사용자 (글쓰기·중고 올리기·쪽지는 인증이 있어야 화면이 열린다)
export const verifiedUser: FakeUser = { ...fakeUser, verifiedSchool: { id: 1, name: '가짜대학교' } }

const postSchool: School = { id: 1, name: '가짜대학교' }
const fakeSchools = [{ id: 1, name: '가짜대학교', emailDomains: ['fake.ac.kr'] }]
// 말머리 코드는 실제와 같게 CHAT·QUESTION·CAMPUS_LIFE
const fakeCategories = [
  { code: 'CHAT', name: '잡담' },
  { code: 'QUESTION', name: '질문' },
  { code: 'CAMPUS_LIFE', name: '학교생활' },
]

// 목록은 최신순(id 내림차순)이라 id 2가 먼저다
export const fakePosts = [
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
const fakeComments = [
  { id: 1, postId: 1, content: '첫 댓글', createdAt: '2026-01-01T01:00:00Z', author: { id: 3, nickname: '글쓴이B' } },
]

// 중고 글 상세 (docs/api.md 28번). 사진은 없음. 판매자는 남(id 7)이라 쪽지를 보낼 수 있다
export const fakeMarketItem = {
  id: 1,
  title: '가짜 자전거',
  content: '거의 새 자전거입니다.',
  tradeType: { code: 'SALE', name: '판매' },
  price: 5000,
  status: { code: 'ON_SALE', name: '판매중' },
  school: postSchool,
  seller: { id: 7, nickname: '판매자닉' },
  createdAt: '2026-01-02T00:00:00Z',
  photos: [] as unknown[],
}

// 쪽지 (docs/api.md 36~39번). 대화 3번: 상대 "판매자닉", 글 1번 "가짜 자전거"
export const fakeConversationDetail = {
  id: 3,
  item: { id: 1, title: '가짜 자전거', deleted: false },
  partner: { id: 7, nickname: '판매자닉' },
  messages: [{ id: 10, content: '안녕하세요, 아직 있나요?', createdAt: '2026-01-02T03:00:00Z', mine: true }],
  hasMore: false,
}

// 단색 PNG를 직접 만든다 (사진 올리기 테스트에서 파일 창에 끼워 넣을 가짜 사진). 앱이 크기를 읽을 수 있는 진짜 PNG여야 한다
export function makePng(r: number, g: number, b: number, size = 8): Buffer {
  const rowLen = size * 3 + 1
  const raw = Buffer.alloc(rowLen * size)
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      const o = y * rowLen + 1 + x * 3
      raw[o] = r
      raw[o + 1] = g
      raw[o + 2] = b
    }
  }
  const crcTable = Array.from({ length: 256 }, (_, n) => {
    let c = n
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
    return c >>> 0
  })
  const crc = (buf: Buffer) => {
    let c = 0xffffffff
    for (const v of buf) c = crcTable[(c ^ v) & 0xff] ^ (c >>> 8)
    return (c ^ 0xffffffff) >>> 0
  }
  const chunk = (type: string, data: Buffer) => {
    const len = Buffer.alloc(4)
    len.writeUInt32BE(data.length)
    const td = Buffer.concat([Buffer.from(type), data])
    const c = Buffer.alloc(4)
    c.writeUInt32BE(crc(td))
    return Buffer.concat([len, td, c])
  }
  const ihdr = Buffer.alloc(13)
  ihdr.writeUInt32BE(size, 0)
  ihdr.writeUInt32BE(size, 4)
  ihdr[8] = 8 // 비트 깊이
  ihdr[9] = 2 // 색 종류 = RGB
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

// ---- 가짜 응답을 만드는 도우미 ----

// status는 상태코드, body는 JSON 본문(없으면 본문 없이 보낸다). { abort: true }는 연결이 끊긴 것처럼(서버 꺼짐 장면)
export type MockResponse = { status: number; body?: unknown } | { abort: true; status?: never }
// 키는 "메서드 경로"(예: "GET /posts"). 경로는 /api를 뗀 것이고 ?뒤 쿼리는 뺀다
export type MockHandlers = Record<string, MockResponse | (() => MockResponse)>

// 사진 파일 주소의 기본 응답(1x1 투명 PNG)
const TINY_PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==',
  'base64',
)

// 기본 가짜 응답 모음. 앱이 시작할 때·로그인한 상단바가 부르는 주소를 모두 준비해 둔다
function defaultHandlers(state: { user: FakeUser | null }): MockHandlers {
  let nextPhotoId = 100
  return {
    // 앱 시작 때 CSRF 헤더 이름을 받는다 (쿠키는 서버 몫이라 본문만)
    'GET /csrf': { status: 200, body: { headerName: 'X-XSRF-TOKEN' } },
    // 로그인 전에는 401, 로그인 뒤에는 내 정보. 401이면 앱이 refresh를 한 번 시도하고 그것도 401이면 비로그인
    'GET /auth/me': () => (state.user ? { status: 200, body: state.user } : { status: 401 }),
    'POST /auth/refresh': () => (state.user ? { status: 200, body: state.user } : { status: 401 }),
    // 로그인에 성공하면 "로그인 상태"로 바뀐다 (이후 /auth/me가 사용자를 돌려준다)
    'POST /auth/login': () => {
      state.user = state.user ?? fakeUser
      return { status: 200, body: state.user }
    },
    'GET /schools': { status: 200, body: fakeSchools },
    'GET /categories': { status: 200, body: fakeCategories },
    'GET /posts': { status: 200, body: fakePosts },
    'GET /posts/1': { status: 200, body: fakePosts[1] },
    'GET /posts/1/comments': { status: 200, body: fakeComments },
    'GET /posts/1/likes/me': { status: 200, body: { liked: false } },
    // 로그인한 화면 상단바: 안 읽은 쪽지 수, 비자 일정 띠 (일정 없음)
    'GET /conversations/unread-count': { status: 200, body: { count: 0 } },
    'GET /users/me/visa-plan': { status: 200, body: { expiryDate: null, daysLeft: null, stage: null, todos: [] } },
    // 중고
    'GET /market-items': { status: 200, body: [] },
    'GET /market-items/1': { status: 200, body: fakeMarketItem },
    // 사진 올리기(33번): 올릴 때마다 사진 id가 1씩 는다
    'POST /market-items/*/photos': () => ({
      status: 201,
      body: { id: nextPhotoId++, width: 1365, height: 2048, thumbWidth: 213, thumbHeight: 320, contentType: 'image/jpeg' },
    }),
    // 쪽지: 쪽지함·대화 보기·읽음 처리
    'GET /conversations': { status: 200, body: [] },
    'GET /conversations/3': { status: 200, body: fakeConversationDetail },
    'PUT /conversations/3/read': { status: 204 },
  }
}

// 키에 "*"가 있으면 그 자리 한 구간과 맞는다. 정확한 키가 없을 때만 쓴다
function findWildcardHandler(handlers: MockHandlers, key: string) {
  for (const pattern of Object.keys(handlers)) {
    if (!pattern.includes('*')) continue
    const p = pattern.split('/')
    const k = key.split('/')
    if (p.length === k.length && p.every((seg, i) => seg === '*' || seg === k[i])) return handlers[pattern]
  }
  return undefined
}

// 첫 로딩(엔진·앱 내려받기와 초기화)을 기다리는 시간. 한 곳에 모아 두고 테스트 제한시간(playwright.config.ts)보다 짧게 둔다.
export const FIRST_LOAD = 30_000

// buffer: 본문을 바이트 그대로 담은 것 (사진 올리기처럼 postData()가 null인 파일 본문을 확인할 때 쓴다)
export type RecordedRequest = { method: string; path: string; body: string | null; buffer: Buffer | null; contentType: string }

// page.route로 /api 요청을 가로채 가짜 응답을 준다. 준비 안 된 요청은 unhandled에 기록하고 404로 막는다
export async function mockApi(page: Page, options: { user?: FakeUser | null } = {}) {
  const state = { user: options.user ?? null }
  const handlers = defaultHandlers(state)
  const unhandled: string[] = []
  const requests: RecordedRequest[] = [] // 지나간 모든 /api 요청 (본문 확인용)
  let pending = 0

  const isApi = (url: string) => new URL(url).pathname.startsWith('/api/')
  page.on('request', (r) => isApi(r.url()) && pending++)
  page.on('requestfinished', (r) => isApi(r.url()) && pending--)
  page.on('requestfailed', (r) => isApi(r.url()) && pending--)

  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const req = route.request()
      const path = new URL(req.url()).pathname.replace(/^\/api/, '')
      const key = `${req.method()} ${path}`
      requests.push({
        method: req.method(),
        path,
        body: req.postData(),
        buffer: req.postDataBuffer(),
        contentType: req.headers()['content-type'] ?? '',
      })
      const handler = handlers[key] ?? findWildcardHandler(handlers, key)
      // 사진 파일 주소(34번)는 따로 준비하지 않아도 작은 PNG를 돌려준다 (상세 화면이 사진을 부르기 때문)
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
      if ('abort' in res) {
        await route.abort('failed') // 연결 끊김: 앱은 응답 없는 네트워크 오류를 받는다
        return
      }
      if (res.body === undefined) await route.fulfill({ status: res.status })
      else await route.fulfill({ status: res.status, json: res.body })
    },
  )
  return {
    unhandled,
    requests,
    // 일부 주소의 가짜 응답을 갈아 끼운다 (예: override({ 'GET /posts': { status: 500 } }))
    override(next: MockHandlers) {
      Object.assign(handlers, next)
    },
    // 페이지를 열기 전에 로그인 상태(사용자)를 정한다. null이면 비로그인
    setUser(user: FakeUser | null) {
      state.user = user
    },
    // 지정한 메서드·경로로 나간 요청만 골라 본다
    sent(method: string, path: string) {
      return requests.filter((r) => r.method === method && r.path === path)
    },
    // 진행 중인 /api 요청이 0이 될 때까지 기다린다 (늦게 온 요청이 검사에서 빠지지 않게)
    async waitIdle() {
      await expect.poll(() => pending, { intervals: [20, 50, 100], timeout: 3000 }).toBe(0)
    },
  }
}

// 매 테스트마다 가짜 API를 자동으로 켜고, 끝나면 "준비 안 한 요청이 없었는지" 검사한다
export const test = base.extend<{ api: Awaited<ReturnType<typeof mockApi>> }>({
  api: [
    // 인자 이름을 provide로 쓴 이유: Playwright 문서는 use라 부르지만, React 쪽 mockApi와 같은 이름으로 맞췄다
    async ({ page }, provide) => {
      // 느린 CI 러너를 로컬에서 흉내 내는 스위치: E2E_CPU_THROTTLE=6 이면 CPU를 6배 느리게 한다 (CDP). 평소(없음)에는 꺼 둔다
      const throttle = Number(process.env.E2E_CPU_THROTTLE || 0)
      if (throttle > 1) {
        const cdp = await page.context().newCDPSession(page)
        await cdp.send('Emulation.setCPUThrottlingRate', { rate: throttle })
      }
      const api = await mockApi(page)
      await provide(api)
      await api.waitIdle()
      // 하나라도 있으면 실패: 앱이 예상 못 한 주소를 불렀다는 뜻
      expect(api.unhandled, '준비하지 않은 /api 요청이 있었다').toEqual([])
    },
    { auto: true },
  ],
})

// ---- Flutter 웹 전용 도우미 ----

// Flutter 웹의 글 입력칸은 locator.fill()이 안 먹는 경우가 있어서
// "칸 클릭 → 포커스가 자리 잡힐 때까지 기다림 → 키보드로 직접 입력 → 그 칸의 값 확인" 방식을 쓴다.
//
// 왜 이렇게 튼튼하게 하나: 느린 CI 러너에서는 Flutter가 포커스를 옮기는 동안 "낡은 입력 요소"가
// 잠깐 activeElement로 남아 있어서, 그때 친 글자가 새 칸(Flutter 컨트롤러)에 안 들어간 적이 있다.
// 그래서 (1) 우리가 누른 그 칸이 포커스를 가졌는지 (2) 그 상태가 연속으로 유지되는지(안정) 보고 친 뒤,
// (3) 그 칸 자체의 값이 기대한 글자인지 확인한다. 값이 틀리면 칸을 비우고 처음부터 다시 시도한다(최대 3번).
export async function typeInto(page: Page, name: string | RegExp, text: string) {
  const field = page.getByRole('textbox', { name }).first()
  for (let attempt = 1; ; attempt++) {
    await field.click()
    await expect(field).toBeFocused()
    // 안정: 그 칸이 연속 3번(약 50ms 간격) 계속 포커스를 가질 때까지 기다린다 (포커스가 옮겨지는 중이면 중간에 풀린다)
    let streak = 0
    await expect
      .poll(async () => (streak = (await field.evaluate((e) => e === document.activeElement)) ? streak + 1 : 0), {
        intervals: [50],
        timeout: 10_000,
      })
      .toBeGreaterThanOrEqual(3)
    await page.keyboard.type(text)
    try {
      // 그 칸 자체(요소)의 값이 방금 친 글자인지 본다. Flutter가 받은 값이 칸에 반영되므로 글자가 빠졌으면 여기서 걸린다
      await expect(field).toHaveValue(text, { timeout: 5_000 })
      return
    } catch (err) {
      if (attempt >= 3) throw err
      // 실패: 칸을 비우고(전체 선택 + 지우기) 다시 시도한다
      await field.click()
      await page.keyboard.press('ControlOrMeta+A')
      await page.keyboard.press('Backspace')
    }
  }
}

// 앱이 뜰 때까지 기다린다: 상단바가 그려지면(비로그인은 "회원가입", 로그인은 "로그아웃" 버튼) 첫 로딩이 끝난 것.
// Flutter 웹의 접근성 트리에서는 상단바 메뉴·목록 글이 link 역할이 아니라 글자(text)로 잡혀서 getByText로 찾는다.
export async function waitForApp(page: Page) {
  await expect(page.getByText('회원가입', { exact: true }).or(page.getByRole('button', { name: '로그아웃' }))).toBeVisible({
    timeout: FIRST_LOAD,
  })
}

export { expect }
