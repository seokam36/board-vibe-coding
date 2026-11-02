import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { NETWORK_ERROR_MESSAGE, getStatus, isNetworkError } from '../errorMessage'
import type { Post, PostUpdateRequest } from '../types'

export default function PostEditPage() {
  const { id } = useParams()
  // encodeURIComponent: 상세 화면과 같은 이유로 id를 경로에 넣기 전에 인코딩한다
  const safeId = encodeURIComponent(id ?? '')
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(true)
  // loadError: 글을 못 불러온 경우(폼 숨김), error: 저장 실패(폼 유지)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const navigate = useNavigate()

  // 들어오면 원래 글을 불러와 폼에 채운다
  useEffect(() => {
    // 늦게 온 응답으로 상태를 바꾸지 않기 위한 플래그 (상세 화면과 같다)
    let ignore = false
    api
      .get<Post>(`/posts/${safeId}`)
      .then((res) => {
        if (ignore) return
        setTitle(res.data.title)
        setContent(res.data.content)
        setLoadError(null)
      })
      .catch((e: unknown) => {
        if (ignore) return
        const status = getStatus(e)
        if (isNetworkError(e)) setLoadError(NETWORK_ERROR_MESSAGE)
        else if (status === 404 || status === 400) setLoadError('글을 찾을 수 없습니다')
        else setLoadError('글을 불러오지 못했습니다')
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [safeId])

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault() // 폼 기본 동작(페이지 새로고침) 막기
    setSaving(true)
    setError(null)
    const body: PostUpdateRequest = { title, content }
    try {
      // 빈 값 검사는 작성 화면처럼 서버의 400 응답에 맡긴다
      await api.put<Post>(`/posts/${safeId}`, body)
      // 수정한 글의 상세 화면으로 이동. replace: 저장한 수정 폼이 뒤로가기 기록에 남지 않게 한다
      navigate(`/posts/${safeId}`, { replace: true })
    } catch (err: unknown) {
      if (isNetworkError(err)) {
        setError(NETWORK_ERROR_MESSAGE)
      } else if (getStatus(err) === 400) {
        setError('제목과 내용을 확인해 주세요 (제목은 최대 100자)')
      } else if (getStatus(err) === 404) {
        setError('글을 찾을 수 없습니다')
      } else {
        setError('저장하지 못했습니다')
      }
      setSaving(false) // 실패했을 때만 버튼을 되살린다 (입력값은 그대로 남는다)
    }
  }

  return (
    <div>
      <h1>글 수정</h1>
      {loading && <p>불러오는 중...</p>}
      {loadError && <p role="alert">{loadError}</p>}
      {/* 글을 불러오는 데 성공했을 때만 폼을 보여준다 */}
      {!loading && !loadError && (
        <form onSubmit={handleSubmit}>
          <div>
            <label>
              제목
              <input
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                maxLength={100}
                placeholder="제목"
                style={{ width: '100%' }}
              />
            </label>
          </div>
          <div>
            <label>
              내용
              <textarea
                value={content}
                onChange={(e) => setContent(e.target.value)}
                rows={10}
                placeholder="내용"
                style={{ width: '100%' }}
              />
            </label>
          </div>
          {error && <p role="alert">{error}</p>}
          <button type="submit" disabled={saving}>
            {saving ? '저장 중...' : '저장'}
          </button>{' '}
          <Link to={`/posts/${safeId}`}>취소</Link>
        </form>
      )}
      {/* 폼이 숨겨진 경우에도 돌아갈 길을 남긴다 */}
      {loadError && <Link to="/">목록으로</Link>}
    </div>
  )
}
