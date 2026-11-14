import { expect, FIRST_LOAD, fakePosts, fakeUser, test, typeInto, waitForApp } from './mockApi'

// 핵심 흐름 3가지: 목록 보기 / 로그인 / 글 상세 보기 (백엔드 없이 가짜 응답으로)
// 옛 React e2e(core-flow.spec.ts, S16-p에서 삭제)와 같은 흐름을 Flutter 웹 화면으로 옮겼다.
test('비로그인으로 목록을 열면 글 2개와 로그인 링크가 보인다', async ({ page }) => {
  await page.goto('/')
  // 첫 로딩이 끝날 때까지 먼저 기다린다 (Flutter 웹은 첫 로딩이 느리다)
  await waitForApp(page)

  // Flutter 웹은 목록 글·상단바 메뉴가 link 역할이 아닌 글자로 잡혀서 getByText로 찾는다
  for (const post of fakePosts) {
    await expect(page.getByText(post.title)).toBeVisible()
  }
  await expect(page.getByText('로그인', { exact: true })).toBeVisible() // 비로그인 상단바: 로그인·회원가입 메뉴
  await expect(page.getByText('회원가입', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '로그아웃' })).toHaveCount(0)
})

test('로그인하면 목록으로 이동하고 상단바에 닉네임과 로그아웃이 보인다', async ({ page, api }) => {
  await page.goto('/login')
  // 첫 로딩이 느려서, 입력칸이 보일 때까지 기다린 뒤에 입력한다
  await expect(page.getByRole('textbox', { name: '아이디' })).toBeVisible({ timeout: FIRST_LOAD })

  await typeInto(page, '아이디', fakeUser.username)
  await typeInto(page, '비밀번호', '아무비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()

  await expect(page).toHaveURL('/')
  await expect(page.getByText(`${fakeUser.nickname} 님`)).toBeVisible()
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
  // 입력한 값이 그대로 서버로 갔는지(칸에 글자가 제대로 들어갔는지)도 확인한다
  expect(JSON.parse(api.sent('POST', '/auth/login')[0].body!)).toEqual({ username: fakeUser.username, password: '아무비밀번호' })
})

test('목록에서 글 제목을 누르면 상세 화면에 제목·내용·작성자가 보인다', async ({ page }) => {
  // 상세 가짜 응답(GET /posts/1)과 같은 글: id 1
  const post = fakePosts.find((p) => p.id === 1)!
  await page.goto('/')
  // 목록이 그려진 뒤에 누른다 (이동 직후 바로 누르면 CI에서 불안정했다)
  await waitForApp(page)
  await page.getByText(post.title).click()

  await expect(page).toHaveURL('/posts/1')
  await expect(page.getByRole('heading', { name: post.title })).toBeVisible()
  await expect(page.getByText(post.content)).toBeVisible()
  await expect(page.getByText(post.author.nickname, { exact: true })).toBeVisible()
})
