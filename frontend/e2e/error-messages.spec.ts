import { expect, fakePosts, fakeUser, loginVerified, test } from './mockApi.ts'
import type { Comment, ReportReason } from '../src/types.ts'

// 에러 문구 화면 테스트: 서버가 실패했을 때 화면이 보여 주는 한국어 문구를 고정한다.
// UI3(문구를 한 곳으로 모으는 리팩터링) 뒤에도 사용자에게 보이는 문구가 그대로인지 지키는 안전망이다.
// 그래서 문구는 src에서 import하지 않고 일부러 여기에 문자열로 직접 적는다
// (src의 상수가 바뀌면 이 테스트가 실패해야 의미가 있다).
//
// 한계: 가짜 /csrf는 쿠키를 주지 않아서 CSRF 쿠키 흐름 자체는 검사하지 못한다.
// 403 테스트는 "403을 받으면 이 문구가 나온다"만 확인한다.

// ---- 장면 준비용 도우미 ----

// 글 1번 본문인데 작성자가 로그인한 사람(fakeUser)인 "내 글"
// (로그인은 api.setLoggedIn(true)로 따로 켠다. 작성자 id가 로그인한 사람과 같아야 앱이 내 글로 본다)
function minePost() {
  return { ...fakePosts[1], author: { id: fakeUser.id, nickname: fakeUser.nickname } }
}

test('글 상세가 404면 "글을 찾을 수 없습니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /posts/1': { status: 404 } })
  await page.goto('/posts/1')

  await expect(page.getByRole('alert')).toHaveText('글을 찾을 수 없습니다')
})

test('글 목록이 500이면 "목록을 불러오지 못했습니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /posts': { status: 500 } })
  await page.goto('/')

  await expect(page.getByRole('alert')).toHaveText('목록을 불러오지 못했습니다')
})

test('없는 학교로 목록을 열어 404면 "존재하지 않는 학교입니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /posts': { status: 404 } })
  await page.goto('/?schoolId=999')

  await expect(page.getByRole('alert')).toHaveText('존재하지 않는 학교입니다')
  // 주소의 schoolId=999가 실제로 화면(학교 select)까지 전달됐는지 확인한다
  await expect(page.getByLabel('학교')).toHaveValue('999')
})

test('요청이 끊기면(네트워크 실패) "서버에 연결할 수 없습니다"가 보인다', async ({ page, api }) => {
  // abort: 응답 자체가 없는 상황(서버 꺼짐 등)을 흉내 낸다
  api.override({ 'GET /posts': { abort: true } })
  await page.goto('/')

  await expect(page.getByRole('alert')).toHaveText('서버에 연결할 수 없습니다')
})

test('로그인이 401이면 "아이디 또는 비밀번호가 틀렸습니다"가 보인다', async ({ page, api }) => {
  api.override({ 'POST /auth/login': { status: 401 } })
  await page.goto('/login')
  await page.getByLabel('아이디').fill('nobody')
  await page.getByLabel('비밀번호').fill('틀린비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()

  await expect(page.getByRole('alert')).toHaveText('아이디 또는 비밀번호가 틀렸습니다')
})

test('로그인이 403(CSRF)이면 보안 토큰 안내 문구가 보인다', async ({ page, api }) => {
  api.override({ 'POST /auth/login': { status: 403 } })
  await page.goto('/login')
  await page.getByLabel('아이디').fill('tester01')
  await page.getByLabel('비밀번호').fill('아무비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()

  await expect(page.getByRole('alert')).toHaveText('보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요')
})

test('본인 글 삭제가 401이고 재발급도 401이면 "다시 로그인해 주세요"가 보이고 주소는 그대로다', async ({ page, api }) => {
  // 로그인한 상태 + 내 글(작성자 id가 로그인한 사람과 같다)
  api.setLoggedIn(true)
  api.override({
    'GET /posts/1': { status: 200, body: minePost() },
    // 삭제가 401 → 앱이 자동 재발급을 시도 → 재발급도 401 → 로그인이 풀린 것으로 본다
    'DELETE /posts/1': { status: 401 },
    'POST /auth/refresh': { status: 401 },
  })
  await page.goto('/posts/1')
  await page.getByRole('button', { name: '삭제' }).click()
  // 삭제 확인 창(dialog) 안의 "삭제" 버튼을 눌러 진짜 삭제를 요청한다
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('다시 로그인해 주세요 로그인')
  // 글 상세는 공개 화면이라 로그인이 풀려도 /login으로 가지 않고 그대로 머문다
  await expect(page).toHaveURL('/posts/1')
})

test('비자 후기 상세가 404면 "후기를 찾을 수 없습니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /visa-reviews/1': { status: 404 } })
  await page.goto('/visa-reviews/1')

  await expect(page.getByRole('alert')).toHaveText('후기를 찾을 수 없습니다')
})

// ---- UI3-a: 옮길 문구를 더 많이 지키는 장면들 ----

test('본인 글 삭제가 403이면 새로고침 안내 문구가 보인다', async ({ page, api }) => {
  // 본인 글인데 403 = CSRF 토큰 문제로 보고 "새로고침"을 안내한다
  api.setLoggedIn(true)
  api.override({
    'GET /posts/1': { status: 200, body: minePost() },
    'DELETE /posts/1': { status: 403 },
  })
  await page.goto('/posts/1')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요')
})

test('관리자가 남의 글 삭제하다 403이면 "삭제할 권한이 없습니다"가 보인다', async ({ page, api }) => {
  // 관리자라서 남의 글에도 삭제 버튼이 보이지만, 서버가 403이면 "권한 없음" 문구가 나온다 (본인 글 문구와 다르다)
  api.setLoggedIn(true)
  api.override({
    'GET /auth/me': { status: 200, body: { ...fakeUser, role: 'ADMIN' } },
    'DELETE /posts/1': { status: 403 },
  })
  await page.goto('/posts/1')
  // 관리자는 댓글 삭제 버튼도 보인다. 글의 삭제 버튼이 화면 순서상 먼저라 first()로 집는다
  await page.getByRole('button', { name: '삭제' }).first().click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('삭제할 권한이 없습니다')
})

test('글 삭제가 404면 "이미 삭제된 글입니다"가 보인다', async ({ page, api }) => {
  // 다른 곳에서 이미 지워진 글을 지우려는 상황
  api.setLoggedIn(true)
  api.override({
    'GET /posts/1': { status: 200, body: minePost() },
    'DELETE /posts/1': { status: 404 },
  })
  await page.goto('/posts/1')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 글입니다')
})

test('회원가입이 403(CSRF)이면 보안 토큰 안내 문구가 보인다', async ({ page, api }) => {
  api.override({ 'POST /auth/signup': { status: 403 } })
  await page.goto('/signup')
  await page.getByLabel('아이디').fill('newuser01')
  await page.getByLabel('닉네임').fill('새회원')
  await page.getByLabel('비밀번호').fill('password1234')
  await page.getByRole('button', { name: '가입' }).click()

  await expect(page.getByRole('alert')).toHaveText('보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요')
})

test('글 상세가 500이면 "글을 불러오지 못했습니다"가 보인다', async ({ page, api }) => {
  // 404·400은 "찾을 수 없습니다"지만 그 밖의 오류는 기본 문구가 나온다
  api.override({ 'GET /posts/1': { status: 500 } })
  await page.goto('/posts/1')

  await expect(page.getByRole('alert')).toHaveText('글을 불러오지 못했습니다')
})

test('글 목록이 400이면 "잘못된 조건입니다" 안내가 보인다', async ({ page, api }) => {
  api.override({ 'GET /posts': { status: 400 } })
  await page.goto('/')

  await expect(page.getByRole('alert')).toHaveText('잘못된 조건입니다. 학교와 말머리를 다시 골라 주세요')
})

test('학교 목록만 500이면 선택지 안내가 뜨고 글 목록은 정상으로 보인다', async ({ page, api }) => {
  // 선택지(학교·말머리) 실패는 목록 본문을 막지 않는다
  api.override({ 'GET /schools': { status: 500 } })
  await page.goto('/')

  // 경고(alert)는 이 안내 하나뿐이어야 한다 (목록 에러 문구가 섞이면 안 된다)
  await expect(page.getByRole('alert')).toHaveCount(1)
  await expect(page.getByRole('alert')).toHaveText('학교·말머리 목록을 불러오지 못했습니다')
  await expect(page.getByText('두 번째 가짜 글')).toBeVisible()
})

test('비자 후기 목록이 404면 "존재하지 않는 학교입니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /visa-reviews': { status: 404 } })
  await page.goto('/visa-reviews?schoolId=999')

  await expect(page.getByRole('alert')).toHaveText('존재하지 않는 학교입니다')
  // 주소의 schoolId=999가 학교 select까지 전달됐는지 확인한다 (목록에 없는 값은 "알 수 없는 학교" option으로 보인다)
  await expect(page.getByLabel('학교')).toHaveValue('999')
})

test('비자 후기 통계가 400이면 "잘못된 조건입니다" 안내가 보인다', async ({ page, api }) => {
  api.override({ 'GET /visa-reviews/stats': { status: 400 } })
  await page.goto('/visa-reviews/stats')

  await expect(page.getByRole('alert')).toHaveText('잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요')
})

test('삭제 401 뒤 재발급이 네트워크로 끊기면 연결 안내가 보이고 로그인은 유지된다', async ({ page, api }) => {
  // 재발급 요청이 아예 못 간 것은 "로그인이 풀린 것"이 아니다 → 로그아웃시키지 않고 연결 문제로 안내한다
  api.setLoggedIn(true)
  api.override({
    'GET /posts/1': { status: 200, body: minePost() },
    'DELETE /posts/1': { status: 401 },
    'POST /auth/refresh': { abort: true },
  })
  await page.goto('/posts/1')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('서버에 연결할 수 없습니다')
  // 상단바에 로그인 닉네임이 그대로 남아 있다
  await expect(page.getByText('테스터 님')).toBeVisible()
})

// ---- UI3-a 보강: 글 상세·댓글·공감·신고 ----

test('본인 글 삭제가 500이면 "삭제하지 못했습니다"가 보인다', async ({ page, api }) => {
  // 표에 없는 상태코드는 기본 문구가 나온다 (403 문구 "...새로고침 후..."와 다르다)
  api.setLoggedIn(true)
  api.override({
    'GET /posts/1': { status: 200, body: minePost() },
    'DELETE /posts/1': { status: 500 },
  })
  await page.goto('/posts/1')
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('삭제하지 못했습니다')
})

test('댓글 등록이 403이면 학교 인증 안내 문구가 보인다', async ({ page, api }) => {
  // 인증한 학교의 글이라 댓글 폼은 열려 있지만, 서버가 403이면 안내 문구가 나온다
  loginVerified(api)
  api.override({ 'POST /posts/1/comments': { status: 403 } })
  await page.goto('/posts/1')
  await page.getByLabel('댓글 내용').fill('안녕하세요')
  await page.getByRole('button', { name: '등록' }).click()

  await expect(page.getByRole('alert')).toHaveText(
    '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
  )
})

test('내 댓글 삭제가 404면 "이미 삭제된 댓글입니다"가 보이고 댓글이 사라진다', async ({ page, api }) => {
  // 댓글 1번의 작성자를 로그인한 사람으로 바꿔서 삭제 버튼이 보이게 한다
  const mine: Comment = {
    id: 1,
    postId: 1,
    content: '내가 쓴 댓글',
    createdAt: '2026-01-01T01:00:00Z',
    author: { id: fakeUser.id, nickname: fakeUser.nickname },
  }
  api.setLoggedIn(true)
  api.override({
    'GET /posts/1/comments': { status: 200, body: [mine] },
    'DELETE /comments/1': { status: 404 },
  })
  await page.goto('/posts/1')
  await expect(page.getByText('내가 쓴 댓글')).toBeVisible()
  // 글은 남의 글이라 삭제 버튼은 내 댓글 것 하나뿐이다
  await page.getByRole('button', { name: '삭제' }).click()
  await page.getByRole('dialog').getByRole('button', { name: '삭제' }).click()

  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 댓글입니다')
  // 이미 지워진 댓글이라 목록에서도 빠진다
  await expect(page.getByText('내가 쓴 댓글')).toHaveCount(0)
})

test('공감이 409면 "이미 공감한 글입니다"가 보인다', async ({ page, api }) => {
  // 서버는 아직 안 눌렀다고 알려 줬는데(liked: false) 누르는 순간 이미 눌려 있던 상황
  loginVerified(api)
  api.override({ 'POST /posts/1/likes': { status: 409 } })
  await page.goto('/posts/1')
  await page.getByRole('button', { name: '공감 (현재 3명)' }).click()

  await expect(page.getByRole('alert')).toHaveText('이미 공감한 글입니다')
})

const fakeReasons: ReportReason[] = [{ code: 'SPAM', name: '스팸' }]

test('글 신고가 404면 "이미 삭제된 글입니다"가 보인다', async ({ page, api }) => {
  // 신고 문구는 "이미 삭제된 {label}입니다" 모양이고, 글에서는 label이 "글"이다
  loginVerified(api)
  api.override({
    'GET /report-reasons': { status: 200, body: fakeReasons },
    'POST /posts/1/reports': { status: 404 },
  })
  await page.goto('/posts/1')
  await page.getByRole('button', { name: '글 신고', exact: true }).click()
  await page.getByLabel('신고 사유').selectOption('SPAM')
  await page.getByRole('button', { name: '신고 보내기' }).click()

  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 글입니다')
})

test('댓글 신고가 404면 "이미 삭제된 댓글입니다"가 보인다', async ({ page, api }) => {
  // 같은 신고 양식이 댓글에서는 label이 "댓글"이 된다
  loginVerified(api)
  api.override({
    'GET /report-reasons': { status: 200, body: fakeReasons },
    'POST /comments/1/reports': { status: 404 },
  })
  await page.goto('/posts/1')
  await page.getByRole('button', { name: '댓글 신고' }).click()
  await page.getByLabel('신고 사유').selectOption('SPAM')
  await page.getByRole('button', { name: '신고 보내기' }).click()

  await expect(page.getByRole('alert')).toHaveText('이미 삭제된 댓글입니다')
})

// ---- UI3-a 보강: 글 수정 ----

test('글 수정 저장이 500이면 "저장하지 못했습니다"가 보인다', async ({ page, api }) => {
  api.setLoggedIn(true)
  api.override({ 'PUT /posts/1': { status: 500 } })
  await page.goto('/posts/1/edit')
  await page.getByRole('button', { name: '저장' }).click()

  await expect(page.getByRole('alert')).toHaveText('저장하지 못했습니다')
})

test('글 수정 저장이 400이면 제목·내용·말머리 확인 안내가 보인다', async ({ page, api }) => {
  api.setLoggedIn(true)
  api.override({ 'PUT /posts/1': { status: 400 } })
  await page.goto('/posts/1/edit')
  await page.getByRole('button', { name: '저장' }).click()

  await expect(page.getByRole('alert')).toHaveText('제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)')
})

// ---- UI3-a 보강: 비자 후기 ----

test('비자 후기 상세가 500이면 "후기를 불러오지 못했습니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /visa-reviews/1': { status: 500 } })
  await page.goto('/visa-reviews/1')

  await expect(page.getByRole('alert')).toHaveText('후기를 불러오지 못했습니다')
})

test('비자 선택지만 500이면 안내가 하나 뜨고 후기 목록은 정상으로 보인다', async ({ page, api }) => {
  api.override({ 'GET /visa-review-options': { status: 500 } })
  await page.goto('/visa-reviews')

  // 경고(alert)는 이 안내 하나뿐이어야 한다
  await expect(page.getByRole('alert')).toHaveCount(1)
  await expect(page.getByRole('alert')).toHaveText('선택지 목록을 불러오지 못했습니다')
  await expect(page.getByText('D-2 유학 · 체류기간 연장 · 처리 10일')).toBeVisible()
})

test('비자 후기 목록이 400이면 "잘못된 조건입니다" 안내가 보인다', async ({ page, api }) => {
  api.override({ 'GET /visa-reviews': { status: 400 } })
  await page.goto('/visa-reviews')

  await expect(page.getByRole('alert')).toHaveText('잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요')
})

test('비자 후기 통계가 404면 "존재하지 않는 학교입니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /visa-reviews/stats': { status: 404 } })
  await page.goto('/visa-reviews/stats?schoolId=999')

  await expect(page.getByRole('alert')).toHaveText('존재하지 않는 학교입니다')
  await expect(page.getByLabel('학교')).toHaveValue('999')
})

test('비자 후기 작성이 400이면 날짜 확인 안내가 보인다', async ({ page, api }) => {
  // 학교 인증을 마친 로그인 사용자만 작성 폼이 열린다
  loginVerified(api)
  api.override({ 'POST /visa-reviews': { status: 400 } })
  await page.goto('/visa-reviews/new')
  await page.getByLabel('비자 종류').selectOption('D_2')
  await page.getByLabel('신청 종류').selectOption('EXTENSION')
  await page.getByLabel('결과', { exact: true }).selectOption('APPROVED')
  // 순서가 거꾸로인 날짜(접수일이 결과일보다 늦다) — 실제 판단은 서버의 400이 한다
  await page.getByLabel('접수일').fill('2026-01-11')
  await page.getByLabel('결과일').fill('2026-01-01')
  await page.getByRole('button', { name: '저장' }).click()

  await expect(page.getByRole('alert')).toHaveText(
    '날짜를 확인해 주세요 (접수일은 결과일과 같거나 앞서야 하고, 둘 다 오늘까지. 본문은 2000자까지)',
  )
})

test('비자 후기 작성이 403이면 학교 인증 안내 문구가 보인다', async ({ page, api }) => {
  // S12-c에서 이 문구를 문구 사전(VISA_SCHOOL_REQUIRED_RETRY)으로 옮겼다. 글자가 그대로인지 지킨다
  loginVerified(api)
  api.override({ 'POST /visa-reviews': { status: 403 } })
  await page.goto('/visa-reviews/new')
  await page.getByLabel('비자 종류').selectOption('D_2')
  await page.getByLabel('신청 종류').selectOption('EXTENSION')
  await page.getByLabel('결과', { exact: true }).selectOption('APPROVED')
  await page.getByLabel('접수일').fill('2026-01-01')
  await page.getByLabel('결과일').fill('2026-01-11')
  await page.getByRole('button', { name: '저장' }).click()

  await expect(page.getByRole('alert')).toHaveText('학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요')
})
