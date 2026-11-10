import { expect, fakePosts, fakeUser, test } from './mockApi.ts'

// 에러 문구 화면 테스트: 서버가 실패했을 때 화면이 보여 주는 한국어 문구를 고정한다.
// UI3(문구를 한 곳으로 모으는 리팩터링) 뒤에도 사용자에게 보이는 문구가 그대로인지 지키는 안전망이다.
// 그래서 문구는 src에서 import하지 않고 일부러 여기에 문자열로 직접 적는다
// (src의 상수가 바뀌면 이 테스트가 실패해야 의미가 있다).
//
// 한계: 가짜 /csrf는 쿠키를 주지 않아서 CSRF 쿠키 흐름 자체는 검사하지 못한다.
// 403 테스트는 "403을 받으면 이 문구가 나온다"만 확인한다.

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
    'GET /posts/1': { status: 200, body: { ...fakePosts[1], author: { id: fakeUser.id, nickname: fakeUser.nickname } } },
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
