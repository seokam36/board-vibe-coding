import { expect, FIRST_LOAD, fakeMarketItem, test, typeInto, verifiedUser, waitForApp } from './mockApi'

// 중고 글 챗봇(S17-d): 로그인 → 상단바 "챗봇" → 키보드로 질문 + Enter → 답·출처 → 출처를 눌러 글 상세로 간다.
// 세세한 규칙(잠금·실패 문구·401·제한 시간 등)은 위젯 테스트 test/chat_page_test.dart가 맡는다.

test('챗봇에 질문하면 답과 참고한 글이 보이고, 출처를 누르면 글 상세로 간다', async ({ page, api }) => {
  api.setUser(verifiedUser)
  api.override({
    // 챗봇 응답(docs/api.md 43번): 답 + 출처 1건
    'POST /chat': {
      status: 200,
      body: {
        answer: '#12번 글에서 자전거를 3만 원에 팔고 있어요.',
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
    // 출처를 누르면 가는 글 상세
    'GET /market-items/12': { status: 200, body: { ...fakeMarketItem, id: 12, title: '출처 자전거', price: 30000 } },
  })

  await page.goto('/')
  await waitForApp(page)
  await page.getByText('챗봇', { exact: true }).click()
  await expect(page).toHaveURL('/chat')
  await expect(page.getByRole('textbox', { name: /질문/ })).toBeVisible({ timeout: FIRST_LOAD })

  // 키보드로 질문을 치고 Enter로 보낸다
  await typeInto(page, /질문/, '3만 원 이하 자전거 있어?')
  await page.keyboard.press('Enter')

  // 답은 status 역할(스크린리더가 도착을 알림)이고 글자는 접근 가능한 이름으로 나온다
  await expect(page.getByRole('status', { name: '#12번 글에서 자전거를 3만 원에 팔고 있어요.' })).toBeVisible()
  // 참고한 글
  await expect(page.getByText('참고한 글', { exact: true })).toBeVisible()
  // 줄 하나가 "제목, 가격, 상태" 한 덩어리로 읽힌다
  // (S28: 줄은 진짜 링크라 link 역할로 찾는다)
  await expect(page.getByRole('link', { name: /^출처 자전거, 30,000원, 판매중/ })).toBeVisible()
  const sent = api.sent('POST', '/chat')
  expect(sent).toHaveLength(1)
  expect(JSON.parse(sent[0].body!)).toEqual({ question: '3만 원 이하 자전거 있어?' })

  // 출처 줄을 누르면 글 상세
  await page.getByRole('link', { name: /^출처 자전거, 30,000원, 판매중/ }).click()
  await expect(page).toHaveURL('/market/12')
})
