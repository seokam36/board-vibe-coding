import { expect, FIRST_LOAD, fakeMarketItem, makePng, test, typeInto, verifiedUser } from './mockApi'

// 중고 글 올리기 + 사진 2장 올리기. 사진 고르기는 브라우저 파일 창(package:web)이라
// flutter test(위젯 테스트)로는 덮을 수 없는 부분이라 e2e로 확인한다.
// React의 market-photo-upload.spec.ts 중 "글쓰기에서 사진 올리고 상세로 이동"만 옮겼다.

test('글쓰기에서 사진 2장을 골라 저장하면 글 올리기 → 사진 2번 올리기 → 상세 이동 순서로 진행된다', async ({ page, api }) => {
  api.setUser(verifiedUser)
  // 이 테스트에서만 판매자를 "나(id 1)"로 한다: 내가 올린 글이어야 상세에 내 글 전용 버튼(수정·삭제)이 보인다
  const mine = { ...fakeMarketItem, seller: { id: 1, nickname: '테스터' } }
  api.override({
    // 글 올리기는 글 1번(사진 없음)을 만들어 준 것으로 응답한다 (201)
    'POST /market-items': { status: 201, body: { ...mine, photos: [] } },
    // 상세는 올라간 사진 2장(id 100·101)이 들어 있는 글을 돌려준다
    'GET /market-items/1': {
      status: 200,
      body: {
        ...mine,
        photos: [100, 101].map((id) => ({ id, width: 1365, height: 2048, thumbWidth: 213, thumbHeight: 320, contentType: 'image/jpeg' })),
      },
    },
  })

  await page.goto('/market/new')
  await expect(page.getByRole('textbox', { name: /제목/ })).toBeVisible({ timeout: FIRST_LOAD })
  await typeInto(page, /제목/, '사진 올리기 글')
  await typeInto(page, /가격/, '5000')
  await typeInto(page, /본문/, '사진 두 장을 올립니다')

  // [사진 추가]를 누르면 브라우저가 파일 창을 연다 → Playwright가 가로채서 PNG 2장을 끼워 준다
  const chooserPromise = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: '사진 추가' }).click()
  const chooser = await chooserPromise
  expect(chooser.isMultiple()).toBe(true) // 여러 장을 한 번에 고를 수 있어야 한다
  await chooser.setFiles([
    { name: 'a.png', mimeType: 'image/png', buffer: makePng(200, 60, 60) },
    { name: 'b.png', mimeType: 'image/png', buffer: makePng(60, 200, 60) },
  ])
  // 사진 칸 제목의 장수가 2장으로 바뀌면 앱이 두 파일을 읽은 것이다 (고정 대기 대신 화면 내용을 기다린다)
  await expect(page.getByText('사진 (2/10)')).toBeVisible()

  await page.getByRole('button', { name: '저장' }).click()

  await expect(page).toHaveURL('/market/1')
  await expect(page.getByRole('heading', { name: fakeMarketItem.title })).toBeVisible()
  // 내 글이라서 내 글 전용 버튼(수정)이 보인다 (판매자가 남이면 안 보이는 버튼)
  await expect(page.getByRole('button', { name: '수정' })).toBeVisible()

  // 순서: POST 요청만 도착한 순서대로 걸러서 "글 → 사진 → 사진"인지 본다
  const posts = api.requests.filter((r) => r.method === 'POST')
  expect(posts.map((r) => r.path)).toEqual(['/market-items', '/market-items/1/photos', '/market-items/1/photos'])
  // 글 올리기 요청 본문
  expect(JSON.parse(posts[0].body!)).toEqual({ title: '사진 올리기 글', content: '사진 두 장을 올립니다', tradeType: 'SALE', price: 5000 })
  // 사진은 multipart로 올라가고, 파일 칸 이름은 file, 파일 이름은 고른 순서(a.png → b.png)다
  // (파일 본문은 postData()가 null이라 바이트로 읽는다)
  const names = ['a.png', 'b.png']
  posts.slice(1).forEach((p, i) => {
    expect(p.contentType).toContain('multipart/form-data')
    const text = p.buffer!.toString('latin1')
    expect(text).toContain('name="file"')
    expect(text).toContain(`filename="${names[i]}"`)
  })
})
