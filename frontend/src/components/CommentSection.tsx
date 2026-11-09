import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link } from 'react-router'
import api from '../api'
import { describeError, errorText } from '../errorMessage'
import type { ErrorInfo } from '../errorMessage'
import { useAuth } from '../auth/useAuth'
import { isAdmin } from '../auth/isAdmin'
import { formatDate } from '../formatDate'
import Loading from './Loading'
import Notice from './Notice'
import ReloginNotice from './ReloginNotice'
import ReportForm from './ReportForm'
import { useConfirm } from './confirmContext'
import type { Comment } from '../types'

const CONTENT_ERROR = '내용을 입력해 주세요 (최대 1000자)'

// 상세 화면의 댓글 영역: 목록 + 작성 + (내 댓글) 수정·삭제
// canComment: 댓글 작성·신고를 허용할지 (로그인했는데 미인증이거나 다른 학교 글이면 false)
export default function CommentSection({ postId, canComment }: { postId: string; canComment: boolean }) {
  const { user } = useAuth()
  const confirm = useConfirm() // 삭제 전 확인 창
  const base = `/posts/${encodeURIComponent(postId)}/comments`

  // null이면 아직 못 불러온 상태
  const [comments, setComments] = useState<Comment[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  // 값을 올리면 목록을 다시 불러온다 ("다시 시도" 버튼용)
  const [reloadKey, setReloadKey] = useState(0)

  // 새 댓글 입력
  const [newText, setNewText] = useState('')
  const [posting, setPosting] = useState(false)
  // 수정 중인 댓글 id와 입력값
  const [editingId, setEditingId] = useState<number | null>(null)
  const [editText, setEditText] = useState('')
  // 저장·삭제 요청 중인 동안 버튼을 막는다 (연타 방지)
  const [busy, setBusy] = useState(false)
  // 쓰기 실패 안내. 불러오기 에러와 따로 둬서 목록은 계속 보인다
  const [actionError, setActionError] = useState<string | null>(null)
  // 쓰기 중 401(재발급까지 실패): 로그인 안내를 보여 준다
  const [needLogin, setNeedLogin] = useState(false)

  useEffect(() => {
    let ignore = false
    api
      .get<Comment[]>(base)
      .then((res) => {
        if (ignore) return
        setComments(res.data)
        setLoadError(null)
      })
      .catch((e: unknown) => {
        if (ignore) return
        setLoadError(errorText(e, {}, '댓글을 불러오지 못했습니다'))
      })
    return () => {
      ignore = true
    }
  }, [base, reloadKey])

  function retryLoad() {
    setLoadError(null)
    setReloadKey((k) => k + 1)
  }

  // 쓰기 요청이 실패했을 때 공통 안내: 로그인이 풀렸으면 로그인 안내, 아니면 문구를 띄운다
  function showError(info: ErrorInfo) {
    if (info.kind === 'needLogin') setNeedLogin(true)
    else setActionError(info.message)
  }

  function resetNotices() {
    setActionError(null)
    setNeedLogin(false)
  }

  async function handleCreate(e: FormEvent<HTMLFormElement>) {
    e.preventDefault()
    resetNotices()
    setPosting(true)
    try {
      // 빈 내용 검사는 프론트에서 하지 않고 서버의 400을 그대로 확인한다 (글쓰기와 같은 방식)
      const res = await api.post<Comment>(base, { content: newText })
      // 오래된 순이라 맨 뒤에 붙이고, 입력칸을 비운다
      setComments((prev) => [...(prev ?? []), res.data])
      setNewText('')
    } catch (err: unknown) {
      // 403: 남의 댓글 (CSRF 토큰 오류도 403이라 구분이 안 된다 — 글 삭제와 같이 처리하지 않음)
      showError(
        describeError(
          err,
          {
            400: CONTENT_ERROR,
            403: '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
            404: '글을 찾을 수 없습니다' },
          '댓글을 등록하지 못했습니다',
        ),
      )
    } finally {
      setPosting(false)
    }
  }

  function startEdit(c: Comment) {
    resetNotices()
    setEditingId(c.id)
    setEditText(c.content)
  }

  async function handleSave(c: Comment) {
    resetNotices()
    setBusy(true)
    try {
      const res = await api.put<Comment>(`/comments/${c.id}`, { content: editText })
      setComments((prev) => (prev ?? []).map((x) => (x.id === c.id ? res.data : x)))
      setEditingId(null)
    } catch (err: unknown) {
      const info = describeError(
        err,
        { 400: CONTENT_ERROR, 403: '본인 댓글만 수정할 수 있습니다', 404: '이미 삭제된 댓글입니다' },
        '수정하지 못했습니다',
      )
      showError(info)
      // 이미 지워진 댓글이면 목록에서도 빼고 편집을 끝낸다
      if (info.kind === 'message' && info.status === 404) {
        setComments((prev) => (prev ?? []).filter((x) => x.id !== c.id))
        setEditingId(null)
      }
    } finally {
      setBusy(false)
    }
  }

  async function handleDelete(c: Comment) {
    // 되돌릴 수 없는 동작이라 먼저 확인한다
    if (!(await confirm({ message: '이 댓글을 삭제할까요?', confirmLabel: '삭제', danger: true }))) return
    resetNotices()
    setBusy(true)
    try {
      await api.delete(`/comments/${c.id}`)
      setComments((prev) => (prev ?? []).filter((x) => x.id !== c.id))
    } catch (err: unknown) {
      // 403: 남의 댓글이면 권한 없음, 본인 댓글이면 CSRF 토큰 오류 가능성이라 새로고침을 안내한다
      const mine = !!user && c.author?.id === user.id
      const info = describeError(
        err,
        {
          403: mine ? '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요' : '삭제할 권한이 없습니다',
          404: '이미 삭제된 댓글입니다',
        },
        '삭제하지 못했습니다',
      )
      showError(info)
      if (info.kind === 'message' && info.status === 404) setComments((prev) => (prev ?? []).filter((x) => x.id !== c.id))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="comments">
      <h2>댓글{comments ? ` ${comments.length}` : ''}</h2>
      {loadError && (
        <Notice kind="error">
          {loadError}{' '}
          <button type="button" className="btn btn-sm" onClick={retryLoad}>
            다시 시도
          </button>
        </Notice>
      )}
      {/* 아직 응답 전이면 비어 있는 게 아니라 불러오는 중이라고 알린다 */}
      {comments === null && !loadError && <Loading prefix="댓글" />}
      {/* 글 목록과 같은 판 스타일: 흰 판 하나 안의 행들 */}
      {comments && (
        <div className="panel panel-flush">
          {comments.length === 0 && <p className="panel-state">아직 댓글이 없습니다</p>}
          <ul className="rows">
            {comments.map((c) => (
              <li key={c.id} className="row">
                <p className="meta">
                  <span>{c.author?.nickname ?? '(작성자 없음)'}</span>
                  <span>{formatDate(c.createdAt)}</span>
                </p>
                {editingId === c.id ? (
                  // 인라인 편집: 내용이 textarea로 바뀌고 저장/취소가 나온다
                  <div>
                    <textarea
                      aria-label="댓글 수정"
                      value={editText}
                      onChange={(e) => setEditText(e.target.value)}
                      rows={3}
                      className="full-width"
                    />
                    <div className="actions">
                      <button type="button" className="btn btn-sm btn-primary" onClick={() => handleSave(c)} disabled={busy}>
                        {busy ? '저장 중...' : '저장'}
                      </button>
                      <button type="button" className="btn btn-sm" onClick={() => setEditingId(null)} disabled={busy}>
                        취소
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    {/* pre-wrap: 줄바꿈을 그대로 보여준다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
                    <p className="pre-wrap">{c.content}</p>
                    <div className="actions">
                      {/* 수정은 내 댓글만, 삭제는 내 댓글 또는 관리자. 화면에서 숨기는 건 편의일 뿐, 진짜 보호는 서버의 403이다 */}
                      {user && c.author?.id === user.id && (
                        <button type="button" className="btn btn-sm" onClick={() => startEdit(c)} disabled={busy}>
                          수정
                        </button>
                      )}
                      {user && (c.author?.id === user.id || isAdmin(user)) && (
                        <button type="button" className="btn btn-sm btn-danger" onClick={() => handleDelete(c)} disabled={busy}>
                          삭제
                        </button>
                      )}
                      {/* 신고: 로그인했고 내 댓글이 아닐 때만 (인증한 학교의 글일 때만 — canComment) */}
                      {user && canComment && c.author?.id !== user.id && (
                        <ReportForm
                          url={`/comments/${c.id}/reports`}
                          label="댓글"
                          onNeedLogin={() => {
                            resetNotices()
                            setNeedLogin(true) // 로그인 안내는 아래 user 조건 밖에서 보여 준다
                          }}
                        />
                      )}
                    </div>
                  </>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
      {/* 실패 안내는 목록·입력칸 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게) */}
      {actionError && <Notice kind="error">{actionError}</Notice>}
      {needLogin && <ReloginNotice />}
      {user && !canComment ? (
        // 미인증·다른 학교 글: 작성 폼 대신 짧은 안내 (자세한 안내는 글 위쪽에 있다)
        <p className="hint">댓글은 인증한 학교의 글에만 쓸 수 있어요</p>
      ) : user ? (
        <form className="comment-form" onSubmit={handleCreate}>
          <textarea
            aria-label="댓글 내용"
            value={newText}
            onChange={(e) => setNewText(e.target.value)}
            rows={3}
            placeholder="댓글을 입력하세요"
            className="full-width"
          />
          <button type="submit" className="btn btn-primary" disabled={posting}>
            {posting ? '등록 중...' : '등록'}
          </button>
        </form>
      ) : (
        <p>
          로그인하면 댓글을 쓸 수 있습니다 <Link to="/login">로그인</Link>
        </p>
      )}
    </section>
  )
}
