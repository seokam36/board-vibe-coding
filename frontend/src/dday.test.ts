import { describe, it, expect } from 'vitest'
import { addYears, daysBetween, formatDDay } from './dday'
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

// 두 날짜 문자열의 일수 차(to - from). 시간대와 무관하게 UTC 자정끼리 빼므로 TZ를 바꿔도 같다
describe('daysBetween', () => {
  it('같은 날이면 0', () => {
    expect(daysBetween('2026-11-10', '2026-11-10')).toBe(0)
  })

  it('달을 넘겨도 하루 차이는 1', () => {
    expect(daysBetween('2026-11-30', '2026-12-01')).toBe(1)
  })

  it('해를 넘겨도 하루 차이는 1', () => {
    expect(daysBetween('2026-12-31', '2027-01-01')).toBe(1)
  })

  it('윤일(2028-02-29)이 낀 구간은 2일', () => {
    expect(daysBetween('2028-02-28', '2028-03-01')).toBe(2)
  })

  it('순서가 반대면 음수', () => {
    expect(daysBetween('2026-12-01', '2026-11-30')).toBe(-1)
    expect(daysBetween('2027-01-01', '2026-12-31')).toBe(-1)
  })

  it('만료 2026-12-10의 120일 전(2026-08-12)은 120', () => {
    expect(daysBetween('2026-08-12', '2026-12-10')).toBe(120)
  })
})
