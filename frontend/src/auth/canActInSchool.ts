import type { AuthUser } from './useAuth'

// 글쓰기·댓글·공감·신고는 "학교 인증을 마친 회원이, 인증한 학교의 글에만" 할 수 있다 (S10-d).
// 학교가 없는 옛 글(schoolId가 없음)은 아무도 못 한다. 화면에서 막는 건 편의일 뿐, 진짜 보호는 서버의 403이다
export function canActInSchool(user: AuthUser | null, postSchoolId: number | null | undefined): boolean {
  return !!user?.verifiedSchool && postSchoolId != null && user.verifiedSchool.id === postSchoolId
}
