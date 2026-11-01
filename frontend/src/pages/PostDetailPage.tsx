import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router'
import api from '../api'
import { NETWORK_ERROR_MESSAGE, getStatus, isNetworkError } from '../errorMessage'
import { formatDate } from '../formatDate'
import type { Post } from '../types'

export default function PostDetailPage() {
  // useParams: 주소의 :id 부분을 꺼낸다. 항상 문자열(또는 undefined)이다
  const { id } = useParams()
  const [post, setPost] = useState<Post | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

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

  return (
    <div>
      {loading && <p>불러오는 중...</p>}
      {error && <p role="alert">{error}</p>}
      {post && (
        <article>
          <h1>{post.title}</h1>
          <small>{formatDate(post.createdAt)}</small>
          {/* pre-wrap: 내용의 줄바꿈·공백을 그대로 보여준다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
          <p style={{ whiteSpace: 'pre-wrap' }}>{post.content}</p>
        </article>
      )}
      <Link to="/">목록으로</Link>
    </div>
  )
}
