import { expect, FIRST_LOAD, test, typeInto } from './mockApi'

// 대표 실패 3가지: 서버가 실패했을 때 화면이 보여 주는 한국어 문구 (문구는 lib/errors/error_messages.dart 기준).
// 문구는 앱 코드에서 가져오지 않고 일부러 여기에 문자열로 직접 적는다 (앱의 글자가 바뀌면 이 테스트가 실패해야 의미가 있다).
// 에러 안내는 role="alert"로 잡힌다. 다만 Flutter 웹은 글자를 aria-label(접근 가능한 이름)로 내보내서 toHaveText가 아니라 toHaveAccessibleName으로 확인한다.

test('글 상세가 404면 "글을 찾을 수 없습니다"가 보인다', async ({ page, api }) => {
  api.override({ 'GET /posts/1': { status: 404 } })
  await page.goto('/posts/1')

  await expect(page.getByRole('alert')).toHaveAccessibleName('글을 찾을 수 없습니다', { timeout: FIRST_LOAD })
})

test('요청이 끊기면(네트워크 실패) "서버에 연결할 수 없습니다"가 보인다', async ({ page, api }) => {
  // abort: 응답 자체가 없는 상황(서버 꺼짐 등)을 흉내 낸다
  api.override({ 'GET /posts': { abort: true } })
  await page.goto('/')

  await expect(page.getByRole('alert')).toHaveAccessibleName('서버에 연결할 수 없습니다', { timeout: FIRST_LOAD })
})

test('로그인이 401이면 "아이디 또는 비밀번호가 틀렸습니다"가 보인다', async ({ page, api }) => {
  api.override({ 'POST /auth/login': { status: 401 } })
  await page.goto('/login')
  await expect(page.getByRole('textbox', { name: '아이디' })).toBeVisible({ timeout: FIRST_LOAD })

  await typeInto(page, '아이디', 'nobody')
  await typeInto(page, '비밀번호', '틀린비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()

  await expect(page.getByRole('alert')).toHaveAccessibleName('아이디 또는 비밀번호가 틀렸습니다')
  await expect(page).toHaveURL('/login') // 실패하면 로그인 화면에 그대로 남는다
})
