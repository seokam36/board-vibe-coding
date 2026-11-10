import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { describeError, errorText, ERROR_MESSAGES } from '../errorMessage'
import { isAdmin } from '../auth/isAdmin'
import { canActInSchool } from '../auth/canActInSchool'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import { useAuth } from '../auth/useAuth'
import CommentSection from '../components/CommentSection'
import LikeButton from '../components/LikeButton'
import ReloginNotice from '../components/ReloginNotice'
import ReportForm from '../components/ReportForm'
import SchoolGateNotice from '../components/SchoolGateNotice'
import { useConfirm } from '../components/confirmContext'
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
  const confirm = useConfirm() // 삭제 전 확인 창
  const isMine = !!user && post?.author?.id === user.id // 내 글인지
  // 댓글·공감·신고는 인증한 학교의 글에서만 (S10-d). 비로그인은 기존 화면(로그인 링크)이라 여기서 막지 않는다
  const canAct = canActInSchool(user, post?.school?.id)

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
        setError(
          errorText(
            e,
            { 404: ERROR_MESSAGES.POST_NOT_FOUND, 400: ERROR_MESSAGES.POST_NOT_FOUND },
            ERROR_MESSAGES.POST_LOAD_FAILED,
          ),
        )
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
    if (!(await confirm({ message: '이 글을 삭제할까요?', confirmLabel: '삭제', danger: true }))) return
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
          403: isMine ? ERROR_MESSAGES.DELETE_RETRY_AFTER_RELOAD : ERROR_MESSAGES.DELETE_FORBIDDEN,
          404: ERROR_MESSAGES.alreadyDeleted('글'),
        },
        ERROR_MESSAGES.DELETE_FAILED,
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
        <article className="panel">
          {/* 말머리와 학교 (학교 없는 글은 "학교 미지정") */}
          <div className="badges">
            <span className={`badge badge-${post.category.code}`}>{post.category.name}</span>
            <span className="badge badge-school">{post.school?.name ?? '학교 미지정'}</span>
          </div>
          <h1 className="post-title">{post.title}</h1>
          <p className="meta">
            <span>{post.author?.nickname ?? '(작성자 없음)'}</span>
            <span>{formatDate(post.createdAt)}</span>
          </p>
          {/* pre-wrap: 내용의 줄바꿈·공백을 그대로 보여준다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
          <p className="pre-wrap post-body">{post.content}</p>
        </article>
      )}
      {/* 작업 줄: 공감 / 수정·삭제 / 신고 */}
      {post && (
        <div className="actions">
          <LikeButton postId={id ?? ''} initialCount={post.likeCount} canLike={!user || canAct} describedBy={user && !canAct ? 'school-gate-notice' : undefined} />
          {/* 수정은 내 글만, 삭제는 내 글 또는 관리자. 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버의 403이다 */}
          {user && isMine && (
            <Link to={`/posts/${encodeURIComponent(id ?? '')}/edit`} className="btn btn-sm">
              수정
            </Link>
          )}
          {user && (isMine || isAdmin(user)) && (
            <button type="button" className="btn btn-sm btn-danger" onClick={handleDelete} disabled={deleting}>
              {deleting ? '삭제 중...' : '삭제'}
            </button>
          )}
          {/* 신고는 로그인했고 내 글이 아니고 인증한 학교의 글일 때만 (학교 인증 + 인증한 학교의 글일 때만 — canAct) */}
          {user && !isMine && canAct && (
            <ReportForm
              url={`/posts/${encodeURIComponent(id ?? '')}/reports`}
              label="글"
              onNeedLogin={() => {
                setDeleteError(null)
                setNeedLogin(true) // 로그인 안내는 아래 user 조건 밖에서 보여 준다
              }}
            />
          )}
        </div>
      )}
      {/* 로그인했지만 공감·신고를 막은 이유 (이미 누른 공감의 취소는 가능) */}
      {post && user && !canAct && <SchoolGateNotice user={user} noSchool={!post.school} id="school-gate-notice" />}
      {/* 삭제 실패 안내는 버튼 영역 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게) */}
      {deleteError && <Notice kind="error">{deleteError}</Notice>}
      {needLogin && <ReloginNotice />}
      {post && <CommentSection postId={id ?? ''} canComment={!user || canAct} />}
      <p>
        <Link to="/" className="btn">
          목록으로
        </Link>
      </p>
    </div>
  )
}
