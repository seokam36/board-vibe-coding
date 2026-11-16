import { expect, FIRST_LOAD, fakeUser, makePng, test, verifiedUser } from './mockApi'

// 유학생 인증 신청 (S20-g1): 내 정보(/me)에서 동의 체크 → 진짜 파일 창으로 사진 한 장 → [인증 신청].
// 파일 창(package:web)과 multipart 본문은 위젯 테스트로 덮을 수 없어서 e2e로 확인한다.

test('동의에 체크하고 사진 한 장을 골라 신청하면 multipart(file·consent)로 올라가고, 승인 응답이면 인증 뱃지가 나타난다', async ({ page, api }) => {
  api.setUser(fakeUser) // 미인증 회원
  let approved = false // 신청이 들어오면 true: 그 뒤의 /auth/me는 인증 회원을 돌려준다
  api.override({
    // 가짜 서버가 바로 자동 승인(201 APPROVED)으로 답한다
    'POST /users/me/permit-verification': () => {
      approved = true
      return {
        status: 201,
        body: { status: 'APPROVED', requestedAt: '2026-11-16T05:12:34.123456Z', decidedAt: '2026-11-16T05:12:35.012345Z' },
      }
    },
    'GET /auth/me': () => ({ status: 200, body: approved ? verifiedUser : fakeUser }),
  })

  await page.goto('/me')
  await expect(page.getByText('유학생 인증', { exact: true })).toBeVisible({ timeout: FIRST_LOAD })
  const submit = page.getByRole('button', { name: '인증 신청' })
  await expect(submit).toBeVisible()
  await expect(submit).toBeDisabled() // 동의도 사진도 없을 때

  await page.getByRole('checkbox').click() // 동의
  await expect(submit).toBeDisabled() // 사진이 아직 없다

  // [사진 고르기]를 누르면 브라우저가 파일 창을 연다 → Playwright가 가로채서 PNG 한 장을 끼워 준다
  const chooserPromise = page.waitForEvent('filechooser')
  await page.getByRole('button', { name: '사진 고르기' }).click()
  const chooser = await chooserPromise
  expect(chooser.isMultiple()).toBe(false) // 허가서는 한 장만 고른다
  await chooser.setFiles([{ name: 'permit.png', mimeType: 'image/png', buffer: makePng(80, 120, 200) }])
  // 고른 파일 이름이 보이면 앱이 파일을 받은 것이다
  await expect(page.getByText('permit.png')).toBeVisible()
  await expect(submit).toBeEnabled()

  await submit.click()

  // 승인 응답이면 회원 정보를 다시 받아(/auth/me) 뱃지가 나타난다. 가짜 서버의 사용자를 인증 회원으로 바꿔 둔다
  await expect(page.getByText('유학생 인증 완료', { exact: false })).toBeVisible()

  // 신청 요청은 1번, multipart, 파일 칸 이름 file, 동의 칸 consent
  const posts = api.sent('POST', '/users/me/permit-verification')
  expect(posts).toHaveLength(1)
  expect(posts[0].contentType).toContain('multipart/form-data')
  const text = posts[0].buffer!.toString('latin1')
  expect(text).toContain('name="file"')
  expect(text).toContain('filename="permit.png"')
  expect(text).toContain('name="consent"')
})
