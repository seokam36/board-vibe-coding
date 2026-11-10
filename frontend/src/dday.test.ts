import { describe, it, expect } from 'vitest'
import { addYears, formatDDay } from './dday'
import { seoulToday } from './formatDate'

// seoulToday는 formatDate.ts로 옮겼다 (비자 일정·후기 폼이 같이 씀)
describe('seoulToday', () => {
  it('UTC 15시 이후는 서울에서 다음 날', () => {
    expect(seoulToday(new Date('2026-11-09T15:30:00Z'))).toBe('2026-11-10')
    expect(seoulToday(new Date('2026-11-09T14:30:00Z'))).toBe('2026-11-09')
  })
})

describe('formatDDay', () => {
  it('남은 날이 많으면 D-n', () => {
    expect(formatDDay(111)).toBe('D-111')
    expect(formatDDay(1)).toBe('D-1')
  })

  it('0이면 D-Day', () => {
    expect(formatDDay(0)).toBe('D-Day')
  })

  it('지났으면 D+n', () => {
    expect(formatDDay(-1)).toBe('D+1')
    expect(formatDDay(-3)).toBe('D+3')
  })
})

describe('addYears', () => {
  it('연도만 더한다', () => {
    expect(addYears('2026-11-10', 5)).toBe('2031-11-10')
  })

  it('없는 날(윤일)이면 그 달 말일로 맞춘다', () => {
    expect(addYears('2028-02-29', 5)).toBe('2033-02-28')
  })
})
