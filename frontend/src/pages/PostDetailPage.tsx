import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { describeError, errorText } from '../errorMessage'
import { isAdmin } from '../auth/isAdmin'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import { useAuth } from '../auth/useAuth'
import CommentSection from '../components/CommentSection'
import LikeButton from '../components/LikeButton'
import ReloginNotice from '../components/ReloginNotice'
import ReportForm from '../components/ReportForm'
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
  const isMine = !!user && post?.author?.id === user.id // 내 글인지

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
        setError(errorText(e, { 404: '글을 찾을 수 없습니다', 400: '글을 찾을 수 없습니다' }, '글을 불러오지 못했습니다'))
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
      // 403: 남의 글이거나, 본인 글인데 CSRF 토큰 오류. 본인이면 새로고침을 안내한다
      const info = describeError(
        err,
        {
          403: isMine ? '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요' : '삭제할 권한이 없습니다',
          404: '이미 삭제된 글입니다',
        },
        '삭제하지 못했습니다',
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setDeleteError(info.message)
      setDeleting(false) // 실패했을 때만 버튼을 되살린다 (성공하면 페이지가 바뀐다)
    }
  }

  return (
    <div>
      {loading && <Loading />}
      {error && <Notice kind="error">{error}</Notice>}
      {post && (
        <article>
          <h1>{post.title}</h1>
          {/* 말머리와 학교 (학교 없는 글은 "학교 미지정") */}
          <small>[{post.category.name}]</small> <small>{post.school?.name ?? '학교 미지정'}</small>
          <br />
          <small>{post.author?.nickname ?? '(작성자 없음)'}</small> <small>{formatDate(post.createdAt)}</small>
          {/* pre-wrap: 내용의 줄바꿈·공백을 그대로 보여준다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
          <p className="pre-wrap">{post.content}</p>
        </article>
      )}
      {/* 공감 버튼과 댓글 영역 (글을 불러온 뒤에만) */}
      {post && <LikeButton postId={id ?? ''} initialCount={post.likeCount} />}
      {/* 수정은 내 글만, 삭제는 내 글 또는 관리자. 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버의 403이다 */}
      {post && user && (isMine || isAdmin(user)) && (
        <div>
          {isMine && <Link to={`/posts/${encodeURIComponent(id ?? '')}/edit`}>수정</Link>}{' '}
          <button type="button" onClick={handleDelete} disabled={deleting}>
            {deleting ? '삭제 중...' : '삭제'}
          </button>
        </div>
      )}
      {/* 신고는 로그인했고 내 글이 아닐 때만 (작성자 없는 옛 글은 신고 가능) */}
      {post && user && !isMine && (
        <ReportForm
          url={`/posts/${encodeURIComponent(id ?? '')}/reports`}
          label="글"
          onNeedLogin={() => {
            setDeleteError(null)
            setNeedLogin(true) // 로그인 안내는 아래 user 조건 밖에서 보여 준다
          }}
        />
      )}
      {/* 삭제 실패 안내는 버튼 영역 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게) */}
      {deleteError && <Notice kind="error">{deleteError}</Notice>}
      {needLogin && <ReloginNotice />}
      {post && <CommentSection postId={id ?? ''} />}
      <Link to="/">목록으로</Link>
    </div>
  )
}
