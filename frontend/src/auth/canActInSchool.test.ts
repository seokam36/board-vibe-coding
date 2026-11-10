import { describe, it, expect } from 'vitest'
import { canActInSchool } from './canActInSchool'
import type { AuthUser } from './useAuth'

// 테스트용 사용자: 인증한 학교만 바꿔 가며 쓴다
const user = (verifiedSchool: AuthUser['verifiedSchool']): AuthUser => ({
  id: 1,
  username: 'u',
  nickname: 'n',
  verifiedSchool,
  role: 'USER',
})

describe('canActInSchool', () => {
  it('인증한 학교와 글의 학교가 같으면 참', () => {
    expect(canActInSchool(user({ id: 7, name: '가나다고' }), 7)).toBe(true)
  })

  it('다른 학교의 글이면 거짓', () => {
    expect(canActInSchool(user({ id: 7, name: '가나다고' }), 8)).toBe(false)
  })

  it('학교 인증을 안 했으면 거짓', () => {
    expect(canActInSchool(user(null), 7)).toBe(false)
  })

  it('비로그인이면 거짓', () => {
    expect(canActInSchool(null, 7)).toBe(false)
  })

  it('학교가 없는 옛 글(null, undefined)이면 거짓', () => {
    expect(canActInSchool(user({ id: 7, name: '가나다고' }), null)).toBe(false)
    expect(canActInSchool(user({ id: 7, name: '가나다고' }), undefined)).toBe(false)
  })
})
