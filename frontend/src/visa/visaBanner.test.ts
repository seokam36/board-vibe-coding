import { describe, it, expect, vi, afterEach } from 'vitest'
import {
  bannerDismissKey,
  bannerDismissValue,
  bannerMessage,
  isBannerDismissed,
  saveBannerDismissed,
} from './visaBanner'

// 메모리에 저장하는 가짜 localStorage
function fakeStorage() {
  const data = new Map<string, string>()
  return {
    data,
    getItem: (k: string) => data.get(k) ?? null,
    setItem: (k: string, v: string) => void data.set(k, v),
  }
}

afterEach(() => vi.unstubAllGlobals())

describe('닫기 키·값', () => {
  it('키는 회원당 하나고 만료일이 들어가지 않는다', () => {
    expect(bannerDismissKey(7)).toBe('visaBannerClosed:7')
    expect(bannerDismissKey(7)).not.toContain('2027')
  })

  it('값에도 만료일 평문이 없다', () => {
    const value = bannerDismissValue('2027-03-01', 'CHECK')
    expect(value).not.toContain('2027')
    expect(value).not.toContain('03-01')
    expect(value.startsWith('CHECK:')).toBe(true)
  })

  it('같은 만료일·단계면 같은 값', () => {
    expect(bannerDismissValue('2027-03-01', 'URGENT')).toBe(bannerDismissValue('2027-03-01', 'URGENT'))
  })

  it('단계가 다르면 다른 값', () => {
    expect(bannerDismissValue('2027-03-01', 'CHECK')).not.toBe(bannerDismissValue('2027-03-01', 'URGENT'))
  })

  it('만료일이 다르면 다른 값', () => {
    expect(bannerDismissValue('2027-03-01', 'CHECK')).not.toBe(bannerDismissValue('2027-03-02', 'CHECK'))
  })
})

describe('bannerMessage', () => {
  it('남은 일수가 D-n으로 들어간다', () => {
    expect(bannerMessage('PREPARE', 111)).toBe('체류 만료 D-111 — 연장·자격 변경 준비를 확인하세요')
    expect(bannerMessage('CHECK', 30)).toBe('체류 만료 D-30 — 신청 방법을 확인하세요')
    expect(bannerMessage('URGENT', 0)).toBe('체류 만료 D-Day — 체류 상태를 확인하세요')
  })

  it('지난 일정은 일수 없이 중립 문구', () => {
    expect(bannerMessage('EXPIRED', -3)).toBe('등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요')
  })
})

describe('bannerMessage - 모르는 단계', () => {
  it('프론트가 모르는 stage면 예외 없이 null을 돌려준다 (띠를 그리지 않게)', () => {
    expect(bannerMessage('NEW_STAGE', 10)).toBeNull()
  })
})

describe('저장 → 읽기', () => {
  it('저장한 값과 같으면 닫힘, 값이 바뀌면 다시 열림', () => {
    vi.stubGlobal('localStorage', fakeStorage())
    const v = bannerDismissValue('2027-03-01', 'CHECK')
    expect(isBannerDismissed(1, v)).toBe(false)
    saveBannerDismissed(1, v)
    expect(isBannerDismissed(1, v)).toBe(true)
    expect(isBannerDismissed(1, bannerDismissValue('2027-03-01', 'URGENT'))).toBe(false)
    expect(isBannerDismissed(2, v)).toBe(false) // 다른 회원
  })

  it('다시 닫으면 덮어써서 키가 하나만 남는다', () => {
    const store = fakeStorage()
    vi.stubGlobal('localStorage', store)
    saveBannerDismissed(1, bannerDismissValue('2027-03-01', 'CHECK'))
    saveBannerDismissed(1, bannerDismissValue('2027-03-01', 'URGENT'))
    expect(store.data.size).toBe(1)
  })
})

describe('저장소가 막힌 브라우저', () => {
  it('읽기·쓰기가 예외를 던져도 터지지 않는다', () => {
    const boom = () => {
      throw new Error('blocked')
    }
    vi.stubGlobal('localStorage', { getItem: boom, setItem: boom })
    expect(isBannerDismissed(1, 'CHECK:abc')).toBe(false)
    expect(() => saveBannerDismissed(1, 'CHECK:abc')).not.toThrow()
  })
})
