import { useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { useAuth } from '../auth/useAuth'
import api from '../api'
import { NETWORK_ERROR_MESSAGE, isNetworkError } from '../errorMessage'
import { formatDate } from '../formatDate'
import type { Post } from '../types'

export default function PostListPage() {
  // useState<타입>(초기값): 화면에 보여줄 값. setXxx로 바꾸면 화면이 다시 그려진다
  const [posts, setPosts] = useState<Post[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const { user } = useAuth()
  const navigate = useNavigate()

  // useEffect(함수, []): 화면이 처음 나타날 때 한 번 실행된다 (목록 불러오기)
  useEffect(() => {
    // 요청 중에 다른 페이지로 이동해 컴포넌트가 사라졌다면, 응답이 와도 상태를 바꾸지 않기 위한 플래그
    let ignore = false
    api
      .get<Post[]>('/posts')
      .then((res) => {
        if (!ignore) setPosts(res.data)
      })
      .catch((e: unknown) => {
        if (!ignore) setError(isNetworkError(e) ? NETWORK_ERROR_MESSAGE : '목록을 불러오지 못했습니다')
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    // 정리(cleanup) 함수: 컴포넌트가 사라질 때 실행된다
    return () => {
      ignore = true
    }
  }, [])

  return (
    <div>
      <h1>게시판</h1>
      {/* 글쓰기 버튼은 항상 보이되, 비로그인이면 로그인 화면으로 보낸다 */}
      <button onClick={() => navigate(user ? '/posts/new' : '/login')}>글쓰기</button>
      {loading && <p>불러오는 중...</p>}
      {error && <p role="alert">{error}</p>}
      {!loading && !error && posts.length === 0 && <p>아직 글이 없습니다</p>}
      <ul>
        {posts.map((post) => (
          // key: 목록의 각 항목을 구분하는 고유값 (React 필수)
          <li key={post.id}>
            <Link to={`/posts/${post.id}`}>{post.title}</Link> <small>{post.author?.nickname ?? '(작성자 없음)'}</small>{' '}
            <small>{formatDate(post.createdAt)}</small>
          </li>
        ))}
      </ul>
    </div>
  )
}
