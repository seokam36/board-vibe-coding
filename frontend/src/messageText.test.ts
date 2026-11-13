import { describe, it, expect } from 'vitest'
import { formatUnreadBadge } from './messageText'

describe('formatUnreadBadge', () => {
  it('0 이하는 빈 문자열(배지 없음)', () => {
    expect(formatUnreadBadge(0)).toBe('')
    expect(formatUnreadBadge(-1)).toBe('')
  })

  it('1~99는 숫자 그대로', () => {
    expect(formatUnreadBadge(1)).toBe('1')
    expect(formatUnreadBadge(99)).toBe('99')
  })

  it('100 이상은 99+', () => {
    expect(formatUnreadBadge(100)).toBe('99+')
    expect(formatUnreadBadge(1234)).toBe('99+')
  })
})
