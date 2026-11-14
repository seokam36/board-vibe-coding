import { expect, FIRST_LOAD, fakePosts, test, typeInto, verifiedUser, waitForApp } from './mockApi'

// 글쓰기 흐름과 보호 경로 문지기 (로그인 안 한 사람이 보호된 주소로 가면 /login으로 보낸다)

test('글쓰기: 말머리·제목·내용을 넣고 저장하면 올바른 요청이 나가고 상세로 이동한다', async ({ page, api }) => {
  api.setUser(verifiedUser) // 글쓰기는 학교 인증을 마친 회원만
  // 서버가 만들어 준 글 1번을 돌려준다 (이후 상세 화면은 GET /posts/1 가짜 응답을 쓴다)
  api.override({ 'POST /posts': { status: 201, body: fakePosts[1] } })

  await page.goto('/posts/new')
  await expect(page.getByRole('textbox', { name: '제목' })).toBeVisible({ timeout: FIRST_LOAD })

  // 말머리 드롭다운: 눌러서 메뉴를 열고 '잡담'을 고른다
  await page.getByRole('button', { name: /말머리/ }).first().click()
  await page.getByRole('menuitem', { name: '잡담' }).click()
  await typeInto(page, '제목', '새로 쓴 글')
  await typeInto(page, '내용', '본문 내용입니다')
  await page.getByRole('button', { name: '저장' }).click()

  await expect(page).toHaveURL('/posts/1')
  await expect(page.getByRole('heading', { name: fakePosts[1].title })).toBeVisible()
  // 요청 본문: 학교는 인증한 학교(1)로, 말머리 코드는 CHAT으로 간다
  const sent = api.sent('POST', '/posts')
  expect(sent).toHaveLength(1)
  expect(JSON.parse(sent[0].body!)).toEqual({ title: '새로 쓴 글', content: '본문 내용입니다', schoolId: 1, category: 'CHAT' })
})

test('비로그인으로 보호된 주소를 열면 로그인 화면으로 보낸다', async ({ page }) => {
  // 주소 3개를 앱을 새로 띄우며 차례로 여니 오래 걸린다: 제한시간을 3배로 늘린다
  test.slow()
  // 보호된 주소 세 가지를 차례로 연다 (글쓰기·내 정보·중고 글쓰기)
  for (const path of ['/posts/new', '/me', '/market/new']) {
    await page.goto(path)
    // 첫 로딩이 끝난 뒤에 주소를 확인한다 (로딩 중에는 로그인 여부를 몰라 문지기가 판단을 미룬다)
    await waitForApp(page)
    await expect(page).toHaveURL('/login')
    await expect(page.getByRole('heading', { name: '로그인' })).toBeVisible()
  }
})
