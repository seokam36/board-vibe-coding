import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { describeError, errorText } from '../errorMessage'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import ReloginNotice from '../components/ReloginNotice'
import type { Category, Post, PostUpdateRequest } from '../types'

export default function PostEditPage() {
  const { id } = useParams()
  // encodeURIComponent: 상세 화면과 같은 이유로 id를 경로에 넣기 전에 인코딩한다
  const safeId = encodeURIComponent(id ?? '')
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  // 학교는 바꿀 수 없어 글자로만 보여 준다 (학교 없는 옛 글은 null)
  const [schoolName, setSchoolName] = useState<string | null>(null)
  // category: 선택된 말머리 code. current: 글이 원래 가진 말머리 (목록을 못 받아도 잃지 않으려고 따로 보관)
  const [category, setCategory] = useState('')
  const [current, setCurrent] = useState<Category | null>(null)
  const [categories, setCategories] = useState<Category[] | null>(null)
  const [categoriesFailed, setCategoriesFailed] = useState(false)
  const [loading, setLoading] = useState(true)
  // loadError: 글을 못 불러온 경우(폼 숨김), error: 저장 실패(폼 유지)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  // 저장 중 401(재발급까지 실패): "저장하지 못했습니다"와 구분해 로그인 안내를 보여 준다
  const [needLogin, setNeedLogin] = useState(false)
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
        setSchoolName(res.data.school?.name ?? null)
        setCategory(res.data.category.code)
        setCurrent(res.data.category)
        setLoadError(null)
      })
      .catch((e: unknown) => {
        if (ignore) return
        setLoadError(errorText(e, { 404: '글을 찾을 수 없습니다', 400: '글을 찾을 수 없습니다' }, '글을 불러오지 못했습니다'))
      })
      .finally(() => {
        if (!ignore) setLoading(false)
      })
    return () => {
      ignore = true
    }
  }, [safeId])

  // 말머리 목록도 불러온다. 실패해도 수정은 막지 않는다 (현재 말머리 하나로 저장할 수 있다)
  useEffect(() => {
    let ignore = false
    api
      .get<Category[]>('/categories')
      .then((res) => {
        if (!ignore) setCategories(res.data)
      })
      .catch(() => {
        if (!ignore) setCategoriesFailed(true)
      })
    return () => {
      ignore = true
    }
  }, [])

  // 드롭다운에 보일 말머리: 받은 목록 + (목록에 없거나 못 받았으면) 글의 현재 말머리
  const options = [...(categories ?? [])]
  if (current && !options.some((c) => c.code === current.code)) options.unshift(current)

  async function handleSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault() // 폼 기본 동작(페이지 새로고침) 막기
    setSaving(true)
    setError(null)
    setNeedLogin(false)
    // 학교는 바꿀 수 없으니 schoolId는 일부러 보내지 않는다
    const body: PostUpdateRequest = { title, content, category }
    try {
      // 빈 값 검사는 작성 화면처럼 서버의 400 응답에 맡긴다
      await api.put<Post>(`/posts/${safeId}`, body)
      // 수정한 글의 상세 화면으로 이동. replace: 저장한 수정 폼이 뒤로가기 기록에 남지 않게 한다
      navigate(`/posts/${safeId}`, { replace: true })
    } catch (err: unknown) {
      // 403: 남의 글. 이 화면은 주소로 직접 들어와도 열리지만 저장은 서버가 막는다
      // (CSRF 토큰 오류도 403이라 구분이 안 된다 — 이번에는 처리하지 않음)
      const info = describeError(
        err,
        {
          403: '본인 글만 수정할 수 있습니다',
          400: '제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)',
          404: '글을 찾을 수 없습니다',
        },
        '저장하지 못했습니다',
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setError(info.message)
      setSaving(false) // 실패했을 때만 버튼을 되살린다 (입력값은 그대로 남는다)
    }
  }

  return (
    <div>
      <h1>글 수정</h1>
      {loading && <Loading />}
      {loadError && <Notice kind="error">{loadError}</Notice>}
      {/* 글을 불러오는 데 성공했을 때만 폼을 보여준다 */}
      {!loading && !loadError && (
        <form onSubmit={handleSubmit}>
          <div>
            {/* 학교는 글자로만 보여 준다 (바꿀 수 없음) */}
            <span>{schoolName ?? '학교 미지정'}</span>{' '}
            <select aria-label="말머리" value={category} onChange={(e) => setCategory(e.target.value)}>
              {options.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name}
                </option>
              ))}
            </select>
            {categoriesFailed && <p>말머리 목록을 불러오지 못했습니다. 지금 말머리로만 저장할 수 있습니다</p>}
          </div>
          <div>
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
          <div>
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
