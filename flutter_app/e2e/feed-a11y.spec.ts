import { expect, fakeMarketItem, fakePosts, test, verifiedUser, waitForApp } from './mockApi'

// S28-c 피드형 목록 줄의 접근성: 줄 하나가 스크린리더에 "링크" 하나로 잡히는지(진짜 <a href>),
// 눌러도 문서가 다시 열리지 않고(앱 재부팅 없음) 앱 안에서 이동하는지, 뒤로 가기가 되는지 본다.
// 줄 모양은 위젯 테스트(test/feed_row_test.dart 등)가 맡고, 여기는 브라우저에서만 보이는 것만 본다.

// 쪽지함 한 줄 (docs/api.md 37번)
const fakeConversationSummary = {
  id: 3,
  item: { id: 1, title: '가짜 자전거', deleted: false },
  partner: { id: 7, nickname: '판매자닉' },
  lastMessage: { content: '안녕하세요, 아직 있나요?', createdAt: '2026-01-02T03:00:00Z', mine: false },
  unreadCount: 0,
}
// 중고 목록 한 줄 (docs/api.md 29번)
const fakeMarketListItem = {
  id: 1,
  title: fakeMarketItem.title,
  tradeType: fakeMarketItem.tradeType,
  price: fakeMarketItem.price,
  status: fakeMarketItem.status,
  school: fakeMarketItem.school,
  createdAt: fakeMarketItem.createdAt,
  coverPhoto: null,
}

test('게시판: 줄이 링크 하나로 잡히고, 누르면 재로드 없이 상세로 갔다가 뒤로 가기로 돌아온다', async ({ page, api }) => {
  const post = fakePosts.find((p) => p.id === 1)!
  await page.goto('/')
  await waitForApp(page)

  // 아래 탭 "게시판"은 버튼 하나 (줄 링크 때문에 이름이 겹쳐 잡히지 않는지 회귀 방지)
  await expect(page.getByRole('button', { name: '게시판' })).toHaveCount(1)

  const link = page.getByRole('link', { name: new RegExp(`^${post.title}`) })
  await expect(link).toHaveCount(1)

  // (a) 진짜 <a href>: 주소 값이 맞다
  await expect(link).toHaveAttribute('href', '/posts/1')
  // (b) 이름 전체: 제목, 닉네임, 말머리, 학교, 댓글, 공감, 시각 (시각은 해가 올해와 다르면 "2026년"이 앞에 붙는다)
  await expect(link).toHaveAccessibleName(
    new RegExp(`^${post.title}, 글쓴이A, 잡담, 가짜대학교, 댓글 1, 공감 3, (\\d{4}년 )?1월 1일 09:00$`),
  )
  // (c) 한 겹: 링크 안에 자식 접근성 노드가 없다 (줄 안쪽 글자가 따로 또 잡히면 두 겹으로 읽힌다)
  expect(await link.locator('flt-semantics').count()).toBe(0)
  // (d) 화면 전체 링크 수 = 목록 글 수 (상단바 메뉴 AppLink는 아직 link가 아니다)
  await expect(page.getByRole('link')).toHaveCount(fakePosts.length)

  // 눌러도 문서가 다시 열리지 않는다 (재로드되면 앱이 처음부터 다시 켜진다)
  let loads = 0
  page.on('load', () => loads++)
  const csrfBefore = api.sent('GET', '/csrf').length // 앱이 켜질 때 한 번 부르는 요청: 재부팅되면 늘어난다
  await link.click()
  await expect(page).toHaveURL('/posts/1')
  await expect(page.getByRole('heading', { name: post.title })).toBeVisible()
  expect(loads).toBe(0)
  expect(api.sent('GET', '/csrf')).toHaveLength(csrfBefore)
  expect(api.sent('GET', '/posts/1')).toHaveLength(1)

  await page.goBack()
  await expect(page).toHaveURL('/')
  await expect(page.getByRole('link', { name: new RegExp(`^${post.title}`) })).toHaveCount(1)
  expect(loads).toBe(0)
  expect(api.sent('GET', '/csrf')).toHaveLength(csrfBefore)
})

test('게시판: 줄에 키보드로 포커스를 주고 Enter를 누르면 재로드 없이 상세로 간다', async ({ page, api }) => {
  await page.goto('/')
  await waitForApp(page)
  const link = page.getByRole('link', { name: /^첫 번째 가짜 글/ })
  await expect(link).toHaveCount(1)

  let loads = 0
  page.on('load', () => loads++)
  await link.focus() // Tab으로 가는 것과 같은 포커스 (Tab 순서는 손 확인표)
  await expect(link).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL('/posts/1')
  await expect(page.getByRole('heading', { name: '첫 번째 가짜 글' })).toBeVisible() // 상세가 그려질 때까지 기다린 뒤 요청을 본다
  expect(loads).toBe(0)
  expect(api.sent('GET', '/posts/1')).toHaveLength(1)
})

test('비자 후기 목록: 줄이 링크 하나로 잡힌다', async ({ page }) => {
  await page.goto('/visa-reviews')
  await waitForApp(page)
  await expect(page.getByRole('link', { name: /^유학\(D-2\) · 연장 · 처리 15일/ })).toHaveCount(1)
})

test('중고 목록: 줄이 링크 하나로 잡히고, 누르면 재로드 없이 상세로 갔다가 뒤로 가기로 돌아온다', async ({ page, api }) => {
  api.override({ 'GET /market-items': { status: 200, body: [fakeMarketListItem] } })
  await page.goto('/market')
  await waitForApp(page)

  const link = page.getByRole('link', { name: /^가짜 자전거, 판매중/ })
  await expect(link).toHaveCount(1)
  await expect(link).toHaveAttribute('href', '/market/1') // 진짜 <a href> 주소
  expect(await link.locator('flt-semantics').count()).toBe(0) // 한 겹
  await expect(page.getByRole('link')).toHaveCount(1) // 화면의 링크 = 목록 글 1개

  let loads = 0
  page.on('load', () => loads++)
  const csrfBefore = api.sent('GET', '/csrf').length // 재부팅되면 늘어나는 요청
  await link.click()
  await expect(page).toHaveURL('/market/1')
  await expect(page.getByText(fakeMarketItem.content)).toBeVisible()
  expect(loads).toBe(0)
  expect(api.sent('GET', '/csrf')).toHaveLength(csrfBefore)
  expect(api.sent('GET', '/market-items/1')).toHaveLength(1)

  await page.goBack()
  await expect(page).toHaveURL('/market')
  await expect(page.getByRole('link', { name: /^가짜 자전거, 판매중/ })).toHaveCount(1)
  expect(loads).toBe(0)
  expect(api.sent('GET', '/csrf')).toHaveLength(csrfBefore)
})

test('쪽지함: 줄이 링크 하나로 잡힌다', async ({ page, api }) => {
  api.setUser(verifiedUser)
  api.override({ 'GET /conversations': { status: 200, body: [fakeConversationSummary] } })
  await page.goto('/messages')
  await waitForApp(page)
  await expect(page.getByRole('link', { name: /^판매자닉/ })).toHaveCount(1)
})

test('내 정보: 바로가기 줄이 링크 하나로 잡힌다', async ({ page, api }) => {
  api.setUser(verifiedUser) // 학교 인증 회원이라 "비자 일정" 바로가기가 보인다
  await page.goto('/me')
  await waitForApp(page)
  await expect(page.getByRole('link', { name: /^비자 일정/ })).toHaveCount(1)
})

test('챗봇: 참고한 글 줄이 링크 하나로 잡힌다', async ({ page, api }) => {
  api.setUser(verifiedUser)
  api.override({
    'POST /chat': {
      status: 200,
      body: {
        answer: '#12번 글에서 자전거를 팔고 있어요.',
        sources: [
          {
            type: 'MARKET_ITEM',
            id: 12,
            title: '출처 자전거',
            tradeType: { code: 'SALE', name: '판매' },
            price: 30000,
            status: { code: 'ON_SALE', name: '판매중' },
          },
        ],
      },
    },
  })
  await page.goto('/chat')
  await waitForApp(page)
  const box = page.getByRole('textbox', { name: /질문/ })
  await box.click()
  await page.keyboard.type('자전거 있어?')
  await page.keyboard.press('Enter')
  await expect(page.getByRole('link', { name: /^출처 자전거, 30,000원, 판매중/ })).toHaveCount(1)
})
