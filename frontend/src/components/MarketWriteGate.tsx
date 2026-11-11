import { Link } from 'react-router'
import type { AuthUser } from '../auth/useAuth'
import Notice from './Notice'

// 중고·나눔 글을 올릴 수 없는 사람(비로그인, 학교 미인증)에게 보여 주는 안내. 비자 후기의 학교 인증 안내와 같은 방식
export default function MarketWriteGate({ user }: { user: AuthUser | null }) {
  return (
    <Notice kind="info">
      {user ? (
        <>
          중고·나눔 글은 학교 인증 후에 올릴 수 있어요 <Link to="/me">내 정보에서 학교 인증하기</Link>
        </>
      ) : (
        <>
          글을 올리려면 로그인이 필요해요 <Link to="/login">로그인</Link>
        </>
      )}
    </Notice>
  )
}
