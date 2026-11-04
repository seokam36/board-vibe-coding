import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { NETWORK_ERROR_MESSAGE, getStatus, isNetworkError } from '../errorMessage'
import { useAuth } from '../auth/useAuth'
import ReloginNotice from '../components/ReloginNotice'
import { formatDate } from '../formatDate'
import type { Post } from '../types'

export default function PostDetailPage() {
  // useParams: 주소의 :id 부분을 꺼낸다. 항상 문자열(또는 undefined)이다
  const { id } = useParams()
  const [post, setPost] = useState<Post | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // 삭제 요청 중인지, 삭제 실패 메시지 (불러오기 에러와 따로 둬서 글은 계속 보이게 한다)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  // 삭제 중 401(재발급까지 실패): "삭제하지 못했습니다"와 구분해 로그인 안내를 보여 준다
  const [needLogin, setNeedLogin] = useState(false)
  const navigate = useNavigate()
  const { user } = useAuth()

  // id가 바뀔 때마다 다시 불러온다 (의존성 배열 [id])
  useEffect(() => {
    // 응답이 오기 전에 컴포넌트가 사라지거나 id가 바뀌면, 늦게 온 응답으로 상태를 바꾸지 않기 위한 플래그
    let ignore = false
    // 지금은 상세→상세 이동이 없어 매번 새로 마운트된다.
    // id만 바뀌는 이동(이전/다음 글 등)이 생기면 loading/error/post 초기화를 추가해야 한다
    api
      // encodeURIComponent: id는 디코딩된 값이라 '?' 같은 문자가 그대로 경로에 섞이면
      // /posts/%3F 가 GET /posts/? (목록 API)로 바뀐다. 인코딩해서 서버가 400/404를 돌려주게 한다
      .get<Post>(`/posts/${encodeURIComponent(id ?? '')}`)
      .then((res) => {
        if (ignore) return
        setPost(res.data)
        setError(null)
      })
      .catch((e: unknown) => {
        if (ignore) return
        setPost(null)
        const status = getStatus(e)
        if (isNetworkError(e)) setError(NETWORK_ERROR_MESSAGE)
        else if (status === 404 || status === 400) setError('글을 찾을 수 없습니다')
        else setError('글을 불러오지 못했습니다')
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [id])

  async function handleDelete() {
    // 되돌릴 수 없는 동작이라 먼저 확인한다. 취소하면 아무것도 하지 않는다
    if (!window.confirm('이 글을 삭제할까요?')) return
    setDeleting(true)
    setDeleteError(null)
    setNeedLogin(false)
    try {
      await api.delete(`/posts/${encodeURIComponent(id ?? '')}`)
      // replace: 삭제된 글의 상세 화면이 뒤로가기 기록에 남지 않게 한다
      navigate('/', { replace: true })
    } catch (err: unknown) {
      if (isNetworkError(err)) setDeleteError(NETWORK_ERROR_MESSAGE)
      else if (getStatus(err) === 401) setNeedLogin(true)
      // 403: 남의 글. (CSRF 토큰 오류도 403이라 구분이 안 된다 — 이번에는 처리하지 않음)
      else if (getStatus(err) === 403) setDeleteError('본인 글만 삭제할 수 있습니다')
      else if (getStatus(err) === 404) setDeleteError('이미 삭제된 글입니다')
      else setDeleteError('삭제하지 못했습니다')
      setDeleting(false) // 실패했을 때만 버튼을 되살린다 (성공하면 페이지가 바뀐다)
    }
  }

  return (
    <div>
      {loading && <p>불러오는 중...</p>}
      {error && <p role="alert">{error}</p>}
      {post && (
        <article>
          <h1>{post.title}</h1>
          <small>{post.author?.username ?? '(작성자 없음)'}</small> <small>{formatDate(post.createdAt)}</small>
          {/* pre-wrap: 내용의 줄바꿈·공백을 그대로 보여준다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
          <p style={{ whiteSpace: 'pre-wrap' }}>{post.content}</p>
        </article>
      )}
      {/* 내 글일 때만 수정·삭제 버튼을 보여준다. 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버의 403이다 */}
      {post && user && post.author?.id === user.id && (
        <div>
          <Link to={`/posts/${encodeURIComponent(id ?? '')}/edit`}>수정</Link>{' '}
          <button type="button" onClick={handleDelete} disabled={deleting}>
            {deleting ? '삭제 중...' : '삭제'}
          </button>
        </div>
      )}
      {/* 삭제 실패 안내는 버튼 영역 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게) */}
      {deleteError && <p role="alert">{deleteError}</p>}
      {needLogin && <ReloginNotice />}
      <Link to="/">목록으로</Link>
    </div>
  )
}
