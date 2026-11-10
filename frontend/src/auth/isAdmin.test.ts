import { describe, it, expect } from 'vitest'
import { isAdmin } from './isAdmin'
import type { AuthUser } from './useAuth'

// 테스트용 사용자: 역할만 바꿔 가며 쓴다
const user = (role: AuthUser['role']): AuthUser => ({
  id: 1,
  username: 'u',
  nickname: 'n',
  verifiedSchool: null,
  role,
})

describe('isAdmin', () => {
  it('ADMIN이면 참', () => {
    expect(isAdmin(user('ADMIN'))).toBe(true)
  })

  it('USER이면 거짓', () => {
    expect(isAdmin(user('USER'))).toBe(false)
  })

  it('비로그인(null, undefined)이면 거짓', () => {
    expect(isAdmin(null)).toBe(false)
    expect(isAdmin(undefined)).toBe(false)
  })
})
