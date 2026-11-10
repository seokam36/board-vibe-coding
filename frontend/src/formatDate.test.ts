import { afterEach, beforeEach, describe, it, expect, vi } from 'vitest'
import { formatDate, formatDay } from './formatDate'

describe('formatDay', () => {
  it('yyyy-MM-dd를 "2026년 9월 1일"로 바꾼다', () => {
    expect(formatDay('2026-09-01')).toBe('2026년 9월 1일')
  })

  it('모양이 다르면 빈 문자열', () => {
    expect(formatDay('2026-9-1')).toBe('')
    expect(formatDay('')).toBe('')
    expect(formatDay('2026-09-01T00:00:00')).toBe('')
  })
})

describe('formatDate', () => {
  // "올해" 판단이 지금 시각에 달려 있으니 지금을 2026-11-10으로 고정하고, 끝나면 원복한다
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-11-10T00:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('올해 시각은 연도 없이 한국 시간으로', () => {
    expect(formatDate('2026-11-08T06:19:00Z')).toBe('11월 8일 15:19')
  })

  it('한국 시간 기준 해가 바뀌면 새해로 계산하고 자정은 00시', () => {
    expect(formatDate('2025-12-31T15:05:00Z')).toBe('1월 1일 00:05')
  })

  it('올해가 아니면 연도를 앞에 붙인다', () => {
    expect(formatDate('2025-11-08T06:19:00Z')).toBe('2025년 11월 8일 15:19')
  })

  it('잘못된 날짜는 빈 문자열', () => {
    expect(formatDate('abc')).toBe('')
  })
})
