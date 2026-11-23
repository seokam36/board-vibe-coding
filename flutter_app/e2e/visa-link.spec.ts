import { expect, FIRST_LOAD, test, waitForApp } from './mockApi'

// S29-b 비자 화면의 링크: 하이코리아(바깥 링크, 새 탭)와 "후기/통계" 탭(링크)이 진짜 <a href>로 잡히는지 본다.
// 모양·높이·앱 경로는 위젯 테스트(test/app_link_test.dart, visa_review_list_page_test.dart)가 맡는다.

const HIKOREA = 'https://www.hikorea.go.kr/'

// 진짜 외부 사이트에 나가지 않게, 새 탭이 열려도 가짜 응답만 준다 (팝업 탭은 page가 아니라 context에서 가로챈다)
async function blockHikorea(page: import('@playwright/test').Page) {
  await page.context().route('https://www.hikorea.go.kr/**', (route) =>
    route.fulfill({ status: 200, contentType: 'text/html', body: '<title>fake</title>' }),
  )
}

// 모든 테스트에 차단을 먼저 건다 (실수로 진짜 사이트에 나가지 않게)
test.beforeEach(async ({ page }) => {
  await blockHikorea(page)
})

test('"하이코리아"는 링크 1개이고 주소가 맞으며, 숨은 <a>에 noopener·새 탭 표시가 있다', async ({ page }) => {
  await page.goto('/visa-reviews')
  await waitForApp(page)
  const link = page.getByRole('link', { name: '하이코리아', exact: true })
  await expect(link).toHaveCount(1)
  await expect(link).toHaveAttribute('href', HIKOREA)
  // 진짜 눌리는 숨은 플랫폼 뷰 <a>에 새 탭·noopener·noreferrer가 다 있는지 (자동 대기 단언)
  await expect(page.locator('a[href="https://www.hikorea.go.kr/"][target="_blank"][rel*="noopener"][rel*="noreferrer"]')).not.toHaveCount(0)
})

test('"하이코리아"를 누르면 새 탭 1개가 열리고, 새 탭은 원래 탭을 못 건드리며, 원래 탭은 그대로다', async ({ page }) => {
  await page.goto('/visa-reviews')
  await waitForApp(page)
  const before = page.url()
  let loads = 0
  page.on('load', () => loads++)
  const [popup] = await Promise.all([
    page.context().waitForEvent('page'),
    page.getByRole('link', { name: '하이코리아', exact: true }).click(),
  ])
  await popup.waitForLoadState()
  expect(popup.url()).toBe(HIKOREA)
  await expect(popup).toHaveTitle('fake') // 차단이 먹어서 가짜 응답이 온 것
  expect(await popup.evaluate(() => window.opener)).toBeNull() // noopener가 실제로 먹었다
  await page.waitForTimeout(1000) // 늦게 하나 더 열리는지 본다 (너무 일찍 세면 거짓 통과)
  expect(page.context().pages()).toHaveLength(2) // 원래 탭 + 새 탭 1개 (중복 없음)
  expect(page.url()).toBe(before)
  expect(loads).toBe(0)
})

test('"하이코리아"에 포커스를 주고 Enter를 눌러도 새 탭은 1개다 (중복 없음)', async ({ page }) => {
  await page.goto('/visa-reviews')
  await waitForApp(page)
  const link = page.getByRole('link', { name: '하이코리아', exact: true })
  await link.focus()
  await expect(link).toBeFocused()
  const [popup] = await Promise.all([page.context().waitForEvent('page'), page.keyboard.press('Enter')])
  await popup.waitForLoadState()
  expect(popup.url()).toBe(HIKOREA)
  await expect(popup).toHaveTitle('fake')
  expect(await popup.evaluate(() => window.opener)).toBeNull()
  await page.waitForTimeout(1000) // 늦게 하나 더 열리는지 본다
  expect(page.context().pages()).toHaveLength(2)
})

test('비자 탭: "통계"는 링크(탭 역할 아님)이고 주소가 맞으며, 현재 탭(후기)만 aria-current가 true다', async ({ page }) => {
  await page.goto('/visa-reviews')
  await waitForApp(page)
  await expect(page.getByRole('tab')).toHaveCount(0)
  const stats = page.getByRole('link', { name: '통계', exact: true })
  const list = page.getByRole('link', { name: '후기', exact: true })
  await expect(stats).toHaveCount(1)
  await expect(stats).toHaveAttribute('href', '/visa-reviews/stats')
  await expect(list).toHaveAttribute('href', '/visa-reviews')
  // 현재 탭 표시: 웹 엔진은 link 역할의 selected를 aria-current로 낸다 (후기 true, 통계 false)
  await expect(list).toHaveAttribute('aria-current', 'true')
  await expect(stats).toHaveAttribute('aria-current', 'false')
})

test('쿼리가 붙은 주소에서 "통계" 링크가 같은 쿼리를 들고, 눌러도 재로드 없이 이동한다', async ({ page, api }) => {
  api.override({ 'GET /visa-reviews/stats': { status: 200, body: [] } })
  await page.goto('/visa-reviews?schoolId=1&visaType=D_2')
  await waitForApp(page)
  const stats = page.getByRole('link', { name: '통계', exact: true })
  await expect(stats).toHaveAttribute('href', '/visa-reviews/stats?schoolId=1&visaType=D_2')

  let loads = 0
  page.on('load', () => loads++)
  await stats.click()
  await expect(page).toHaveURL('/visa-reviews/stats?schoolId=1&visaType=D_2')
  await expect(stats).toHaveAttribute('aria-current', 'true', { timeout: FIRST_LOAD }) // 이동 뒤 현재 탭이 통계로 뒤집힌다
  await expect(page.getByRole('link', { name: '후기', exact: true })).toHaveAttribute('href', '/visa-reviews?schoolId=1&visaType=D_2', { timeout: FIRST_LOAD })
  expect(loads).toBe(0)
})
