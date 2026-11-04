import { useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import api from '../api'
import { NETWORK_ERROR_MESSAGE, getStatus, isNetworkError } from '../errorMessage'
import ReloginNotice from '../components/ReloginNotice'
import type { Post, PostCreateRequest } from '../types'

export default function PostCreatePage() {
  // 입력창 값을 state로 관리한다 (controlled input). 에러가 나도 값이 그대로 남는다
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [saving, setSaving] = useState(false)
  // 저장 중 401(재발급까지 실패): "저장하지 못했습니다"와 구분해 로그인 안내를 보여 준다
  const [needLogin, setNeedLogin] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // useNavigate: 코드에서 다른 주소로 이동할 때 쓰는 함수를 돌려준다
  const navigate = useNavigate()

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault() // 폼 기본 동작(페이지 새로고침) 막기
    setSaving(true)
    setError(null)
    setNeedLogin(false)
    const body: PostCreateRequest = { title, content }
    try {
      // 빈 값 검사는 일부러 프론트에서 하지 않는다. 서버의 400 응답을 그대로 확인한다
      const res = await api.post<Post>('/posts', body)
      navigate(`/posts/${res.data.id}`) // 응답 body의 id로 상세 페이지 이동
    } catch (err: unknown) {
      if (isNetworkError(err)) {
        setError(NETWORK_ERROR_MESSAGE)
      } else if (getStatus(err) === 401) {
        setNeedLogin(true)
      } else if (getStatus(err) === 400) {
        setError('제목과 내용을 확인해 주세요 (제목은 최대 100자)')
      } else {
        setError('저장하지 못했습니다')
      }
      setSaving(false) // 실패했을 때만 버튼을 되살린다 (성공하면 페이지가 바뀐다)
    }
  }

  return (
    <div>
      <h1>글쓰기</h1>
      <form onSubmit={handleSubmit}>
        <div>
          {/* label로 감싸면 스크린리더가 입력창의 이름을 읽을 수 있다 */}
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
        {needLogin && <ReloginNotice />}
        <button type="submit" disabled={saving}>
          {saving ? '저장 중...' : '저장'}
        </button>{' '}
        <Link to="/">목록으로</Link>
      </form>
    </div>
  )
}
