import { Link } from 'react-router'
import type { AuthUser } from '../auth/useAuth'
import Notice from './Notice'

// 로그인했지만 댓글·공감·신고를 할 수 없을 때(미인증, 다른 학교 글, 학교 없는 글) 보여 주는 짧은 안내
// id: 비활성 공감 버튼의 aria-describedby가 가리키는 값. noSchool: 학교가 정해지지 않은 옛 글인지
export default function SchoolGateNotice({ user, noSchool, id }: { user: AuthUser; noSchool: boolean; id?: string }) {
  const school = user.verifiedSchool
  return (
    <Notice kind="info" id={id}>
      {!school ? (
        <>
          학교 인증 후 같은 학교 글에만 댓글·공감·신고할 수 있어요 <Link to="/me">학교 인증하기</Link>
        </>
      ) : noSchool ? (
        '학교가 정해지지 않은 옛 글은 읽기만 할 수 있어요'
      ) : (
        `인증한 학교(${school.name}) 글에만 댓글·공감·신고할 수 있어요`
      )}
    </Notice>
  )
}
