import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate } from 'react-router'
import api from '../api'
import { describeError } from '../errorMessage'
import Notice from '../components/Notice'
import ReloginNotice from '../components/ReloginNotice'
import { useAuth } from '../auth/useAuth'
import type { Category, Post, PostCreateRequest } from '../types'

export default function PostCreatePage() {
  // 입력창 값을 state로 관리한다 (controlled input). 에러가 나도 값이 그대로 남는다
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  // 말머리: 처음엔 아무것도 고르지 않은 상태(''). 학교는 고르지 않고 인증한 학교로 고정된다 (S10)
  const [category, setCategory] = useState('')
  const { user } = useAuth()
  const school = user?.verifiedSchool ?? null // null이면 미인증 → 폼 대신 안내를 보여 준다
  // 말머리 목록. 못 받으면 null로 남고, optionsFailed로 안내한다
  const [categories, setCategories] = useState<Category[] | null>(null)
  const [optionsFailed, setOptionsFailed] = useState(false)
  const [saving, setSaving] = useState(false)
  // 저장 중 401(재발급까지 실패): "저장하지 못했습니다"와 구분해 로그인 안내를 보여 준다
  const [needLogin, setNeedLogin] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // useNavigate: 코드에서 다른 주소로 이동할 때 쓰는 함수를 돌려준다
  const navigate = useNavigate()

  // 화면이 처음 나타날 때 말머리 목록을 한 번 불러온다 (목록 화면과 같은 방식)
  useEffect(() => {
    let ignore = false
    api
      .get<Category[]>('/categories')
      .then((res) => {
        if (!ignore) setCategories(res.data)
      })
      .catch(() => {
        if (!ignore) setOptionsFailed(true)
      })
    return () => {
      ignore = true
    }
  }, [])

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault() // 폼 기본 동작(페이지 새로고침) 막기
    setError(null)
    setNeedLogin(false)
    if (!school) return // 미인증이면 폼 자체가 안 보이지만, 혹시 몰라 막는다
    // 말머리를 안 골랐으면 서버에 보내지 않고 화면에 머문다 (제목·내용은 state에 그대로 남는다)
    if (!category) {
      setError(optionsFailed ? '말머리 목록을 불러오지 못해 저장할 수 없습니다. 새로고침 후 다시 시도해 주세요' : '말머리를 골라 주세요')
      return
    }
    setSaving(true)
    const body: PostCreateRequest = { title, content, schoolId: school.id, category }
    try {
      // 제목·내용의 빈 값 검사는 일부러 프론트에서 하지 않는다. 서버의 400 응답을 그대로 확인한다
      const res = await api.post<Post>('/posts', body)
      navigate(`/posts/${res.data.id}`) // 응답 body의 id로 상세 페이지 이동
    } catch (err: unknown) {
      // 403: 미인증이거나 인증한 학교가 아님. CSRF 토큰 문제도 403이라 구분이 안 되어 새로고침도 함께 안내한다
      const info = describeError(
        err,
        {
          400: '제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)',
          403: '학교 인증이 필요하거나 인증한 학교 게시판이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
        },
        '저장하지 못했습니다',
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setError(info.message)
      setSaving(false) // 실패했을 때만 버튼을 되살린다 (성공하면 페이지가 바뀐다)
    }
  }

  // 미인증: 글을 쓸 수 없으니 폼 대신 안내와 인증 화면 링크를 보여 준다
  if (!school) {
    return (
      <div>
        <h1>글쓰기</h1>
        <div className="panel">
          <p>글쓰기는 학교 인증 후에 할 수 있어요</p>
          <Link to="/me" className="btn btn-primary">
            내 정보에서 학교 인증하기
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div>
      <h1>글쓰기</h1>
      <form className="panel" onSubmit={handleSubmit}>
        <div className="filters">
          {/* 학교는 인증한 학교로 고정: 고르는 칸이 아니라 글자로만 보여 준다 */}
          <span className="badge badge-school">
            <span className="sr-only">학교: </span>
            {school.name}
          </span>
          <select aria-label="말머리" value={category} onChange={(e) => setCategory(e.target.value)}>
            <option value="">말머리 선택</option>
            {(categories ?? []).map((c) => (
              <option key={c.code} value={c.code}>
                {c.name}
              </option>
            ))}
          </select>
        </div>
        {optionsFailed && <Notice kind="error">말머리 목록을 불러오지 못했습니다</Notice>}
        <div className="field">
          {/* label로 감싸면 스크린리더가 입력창의 이름을 읽을 수 있다 */}
          <label>
            제목
            <input
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              maxLength={100}
              placeholder="제목"
              className="full-width"
            />
          </label>
        </div>
        <div className="field">
          <label>
            내용
            <textarea
              value={content}
              onChange={(e) => setContent(e.target.value)}
              rows={10}
              placeholder="내용"
              className="full-width"
            />
          </label>
        </div>
        {error && <Notice kind="error">{error}</Notice>}
        {needLogin && <ReloginNotice />}
        <div className="actions">
          <button type="submit" className="btn btn-primary" disabled={saving}>
            {saving ? '저장 중...' : '저장'}
          </button>
          <Link to="/" className="btn">
            목록으로
          </Link>
        </div>
      </form>
    </div>
  )
}
