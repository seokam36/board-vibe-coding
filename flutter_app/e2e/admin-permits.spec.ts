import { expect, FIRST_LOAD, fakeUser, test } from './mockApi'
import type { FakeUser } from './mockApi'

// 관리자 인증 심사 화면 (S20-g3): 대기 목록이 보이고, [사진 보기]로 진짜 브라우저가 사진(PNG 바이트)을 그리는지 확인한다.
// Image.memory의 실제 디코딩은 위젯 테스트로 확인할 수 없어서 e2e로 본다. 사진은 mockApi의 작은 PNG(실물 허가서 아님).

const admin: FakeUser = { ...fakeUser, id: 9, username: 'admin', nickname: '관리자', role: 'ADMIN' }

test('관리자가 인증 심사 대기 목록에서 [사진 보기]를 누르면 사진이 펼쳐지고, [사진 접기]로 접힌다', async ({ page, api }) => {
  api.setUser(admin)
  api.override({
    'GET /admin/permit-requests': {
      status: 200,
      body: [
        {
          id: 5, userId: 7, nickname: '민수', status: 'PENDING', createdAt: '2026-11-16T05:12:34Z', schoolName: null, rejectReason: null,
          detectedSchoolId: null, detectedSchoolName: null, machineResult: 'UNREADABLE', autoDecided: false, hasPhoto: true,
        },
      ],
    },
  })

  await page.goto('/admin/permits')
  await expect(page.getByText('민수', { exact: true })).toBeVisible({ timeout: FIRST_LOAD })
  await expect(page.getByText('글자를 못 읽음')).toBeVisible()
  expect(api.sent('GET', '/admin/permit-requests')).toHaveLength(1)

  await page.getByRole('button', { name: '사진 보기' }).click()
  await expect(page.getByRole('button', { name: '사진 접기' })).toBeVisible()
  await api.waitIdle()
  // 로딩이 끝나고, 사진이 실제로 그려졌으며(이미지 설명 "허가서 사진"), 실패 문구는 없다
  await expect(page.getByText('사진 불러오는 중...')).toHaveCount(0)
  await expect(page.getByRole('img', { name: '허가서 사진' })).toBeVisible()
  await expect(page.getByText('사진을 표시할 수 없어요')).toHaveCount(0)
  await expect(page.getByText('사진을 불러오지 못했어요')).toHaveCount(0)
  expect(api.sent('GET', '/admin/permit-requests/5/photo')).toHaveLength(1)
  // 사진이 실제로 받아졌고(디코딩 실패 문구 없음), 접으면 다시 [사진 보기]가 나온다
  await page.getByRole('button', { name: '사진 접기' }).click()
  await expect(page.getByRole('button', { name: '사진 보기' })).toBeVisible()
})

// S20-g4a 반려 흐름: [반려] → 사유 고르기 → [반려하기] → 확인 창 [반려] → PUT 1건 → 목록을 다시 읽어 줄이 사라진다.
// 드롭다운·확인 창이 진짜 브라우저에서 눌리는지 위젯 테스트로는 알 수 없어서 e2e로 본다.
test('관리자가 대기 신청을 반려하면 PUT이 1건 나가고 목록에서 줄이 사라진다', async ({ page, api }) => {
  api.setUser(admin)
  const pending = {
    id: 5, userId: 7, nickname: '민수', status: 'PENDING', createdAt: '2026-11-16T05:12:34Z', schoolName: null, rejectReason: null,
    detectedSchoolId: null, detectedSchoolName: null, machineResult: 'UNREADABLE', autoDecided: false, hasPhoto: false,
  }
  let rejected = false // PUT이 나간 뒤에는 목록이 비어 돌아온다
  api.override({
    'GET /admin/permit-requests': () => ({ status: 200, body: rejected ? [] : [pending] }),
    'PUT /admin/permit-requests/5/reject': () => {
      rejected = true
      return { status: 200, body: {} }
    },
  })

  await page.goto('/admin/permits')
  await expect(page.getByText('민수', { exact: true })).toBeVisible({ timeout: FIRST_LOAD })

  // 탭 "반려"와 줄의 [반려]가 같은 글자라 줄 쪽(화면 뒤쪽)을 고른다
  await page.getByRole('button', { name: '반려', exact: true }).last().click()
  await page.getByRole('button', { name: /반려 사유/ }).first().click()
  await page.getByRole('menuitem', { name: '사진이 흐려서 글자를 읽을 수 없어요' }).click()
  await page.getByRole('button', { name: '반려하기', exact: true }).click()

  // 확인 창
  // (Flutter 웹은 확인 창 문구를 글자로 내보내지 않아 창과 그 안의 버튼으로 확인한다. 문구는 위젯 테스트가 본다)
  await expect(page.getByRole('dialog').getByRole('button', { name: '취소' })).toBeVisible()
  expect(api.sent('PUT', '/admin/permit-requests/5/reject')).toHaveLength(0) // 확인 전에는 안 나간다
  await page.getByRole('button', { name: '반려', exact: true }).last().click()

  await expect(page.getByRole('status')).toHaveAccessibleName('반려했어요')
  await expect(page.getByText('민수', { exact: true })).toHaveCount(0)
  await expect(page.getByText('대기 중인 신청이 없어요')).toBeVisible()
  await api.waitIdle()
  const puts = api.sent('PUT', '/admin/permit-requests/5/reject')
  expect(puts).toHaveLength(1)
  expect(JSON.parse(puts[0].body!)).toEqual({ reason: 'BLURRY' })
  expect(api.sent('GET', '/admin/permit-requests')).toHaveLength(2) // 처음 + 처리 뒤 다시 읽기
})
