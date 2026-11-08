import { Link } from 'react-router'
import { RELOGIN_MESSAGE } from '../errorMessage'
import Notice from './Notice'

// 자동 재발급까지 실패해 로그인이 풀렸을 때 쓰기 화면이 보여 주는 안내.
// 화면을 갑자기 /login 으로 바꾸지 않고, 쓰던 내용은 그대로 둔 채 로그인 링크만 알려 준다
export default function ReloginNotice() {
  return (
    <Notice kind="error">
      {RELOGIN_MESSAGE} <Link to="/login">로그인</Link>
    </Notice>
  )
}
