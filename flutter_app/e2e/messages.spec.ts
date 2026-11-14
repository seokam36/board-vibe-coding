import { expect, FIRST_LOAD, fakeConversationDetail, test, typeInto, verifiedUser } from './mockApi'

// 중고 글 상세에서 판매자에게 첫 쪽지를 보내면 대화 화면으로 이동한다.
// React의 messages.spec.ts 중 "보내기 → 대화 이동" 한 가지만 옮겼다.

test('글 상세에서 쪽지를 보내면 올바른 요청이 나가고 대화 화면으로 이동한다', async ({ page, api }) => {
  api.setUser(verifiedUser) // 쪽지 보내기는 학교 인증 회원만 (글 학교 = 인증한 학교)
  // 서버 응답: 대화 3번이 만들어졌고 첫 쪽지가 들어 있다 (docs/api.md 36번)
  api.override({
    'POST /market-items/1/conversations': {
      status: 201,
      body: { conversationId: 3, message: fakeConversationDetail.messages[0] },
    },
  })

  await page.goto('/market/1')
  await expect(page.getByRole('heading', { name: '판매자에게 쪽지' })).toBeVisible({ timeout: FIRST_LOAD })
  await typeInto(page, '쪽지 내용', '안녕하세요, 아직 있나요?')
  await page.getByRole('button', { name: '쪽지 보내기' }).click()

  await expect(page).toHaveURL('/messages/3')
  // 대화 화면: 상대 이름과 방금 보낸 쪽지가 보인다
  await expect(page.getByText('안녕하세요, 아직 있나요?')).toBeVisible()
  await expect(page.getByText('판매자닉').first()).toBeVisible()
  const sent = api.sent('POST', '/market-items/1/conversations')
  expect(sent).toHaveLength(1)
  expect(JSON.parse(sent[0].body!)).toEqual({ content: '안녕하세요, 아직 있나요?' })
  // 대화를 열면 앱이 "마지막 쪽지(id 10)까지 읽음"으로 처리한다 (docs/api.md 40번)
  await expect.poll(() => api.sent('PUT', '/conversations/3/read').map((r) => JSON.parse(r.body!))).toEqual([{ lastMessageId: 10 }])
})
