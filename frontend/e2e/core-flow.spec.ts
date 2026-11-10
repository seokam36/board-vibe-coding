import { expect, fakePosts, fakeUser, test } from './mockApi.ts'

// 핵심 흐름 3가지: 목록 보기 / 로그인 / 글 상세 보기 (백엔드 없이 가짜 응답으로)
test('비로그인으로 목록을 열면 글 2개와 로그인 링크가 보인다', async ({ page }) => {
  await page.goto('/')

  for (const post of fakePosts) {
    await expect(page.getByRole('link', { name: post.title })).toBeVisible()
  }
  await expect(page.getByRole('link', { name: '로그인' })).toBeVisible()
})

test('로그인하면 목록으로 이동하고 상단바에 닉네임과 로그아웃이 보인다', async ({ page }) => {
  await page.goto('/login')

  await page.getByLabel('아이디').fill(fakeUser.username)
  await page.getByLabel('비밀번호').fill('아무비밀번호')
  await page.getByRole('button', { name: '로그인' }).click()

  await expect(page).toHaveURL('/')
  await expect(page.getByText(`${fakeUser.nickname} 님`)).toBeVisible()
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible()
})

test('목록에서 글 제목을 누르면 상세 화면에 제목·내용·작성자가 보인다', async ({ page }) => {
  // 상세 가짜 응답(GET /posts/1)과 같은 글: id 1
  const post = fakePosts.find((p) => p.id === 1)!
  await page.goto('/')
  await page.getByRole('link', { name: post.title }).click()

  await expect(page).toHaveURL('/posts/1')
  await expect(page.getByRole('heading', { name: post.title })).toBeVisible()
  await expect(page.getByText(post.content)).toBeVisible()
  await expect(page.getByText(post.author!.nickname, { exact: true }).first()).toBeVisible()
})
