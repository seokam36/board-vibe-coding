import { useEffect, useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { useAuth } from '../auth/useAuth'
import api from '../api'
import { errorText } from '../errorMessage'
import Loading from '../components/Loading'
import { LOADING_TEXT } from '../components/loadingText'
import Notice from '../components/Notice'
import { SITE_NAME } from '../siteName'
import { formatDate } from '../formatDate'
import type { Category, Post, School } from '../types'

// 글 목록 요청 한 번의 결과. key(어떤 조건으로 요청했는지)를 같이 저장해 둔다
interface ListResult {
  key: string
  posts: Post[]
  error: string | null
}

export default function PostListPage() {
  const notice = (useLocation().state as { message?: string } | null)?.message
  // 필터의 "진짜 값"은 주소(?schoolId=1&category=CHAT)다. 새로고침·뒤로 가기가 자연스럽게 맞는다
  const [searchParams, setSearchParams] = useSearchParams()
  const schoolId = searchParams.get('schoolId') ?? ''
  const category = searchParams.get('category') ?? ''
  // 요청 조건을 한 문자열로 묶은 것. 결과가 "지금 조건의 것"인지 비교하는 데 쓴다
  const queryKey = `${schoolId}|${category}`

  const [result, setResult] = useState<ListResult | null>(null)
  // 드롭다운 목록. 아직 못 받았거나 실패하면 null로 두고 글 목록은 계속 보여 준다
  const [schools, setSchools] = useState<School[] | null>(null)
  const [categories, setCategories] = useState<Category[] | null>(null)
  // 학교·말머리 목록 중 하나라도 실패하면 true
  const [optionsFailed, setOptionsFailed] = useState(false)
  const { user } = useAuth()
  const navigate = useNavigate()

  // 드롭다운 목록은 화면이 처음 나타날 때 한 번만 불러온다 (실패하면 안내 문구를 보여 준다)
  useEffect(() => {
    let ignore = false
    api
      .get<School[]>('/schools')
      .then((res) => {
        if (!ignore) setSchools(res.data)
      })
      .catch(() => {
        if (!ignore) setOptionsFailed(true)
      })
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

  // 주소의 필터가 바뀔 때마다 목록을 다시 불러온다
  useEffect(() => {
    // 조건이 또 바뀌거나 화면이 사라지면 true가 되어, 늦게 도착한 옛 응답이 최신 결과를 덮어쓰지 못한다
    let ignore = false
    // 빈 값은 보내지 않는다(조건 없음). 주소에서 온 값 그대로 보내서 서버가 400/404로 판단하게 한다
    const params: Record<string, string> = {}
    if (schoolId) params.schoolId = schoolId
    if (category) params.category = category
    api
      .get<Post[]>('/posts', { params })
      .then((res) => {
        if (!ignore) setResult({ key: queryKey, posts: res.data, error: null })
      })
      .catch((e: unknown) => {
        if (ignore) return
        const error = errorText(
          e,
          { 404: '존재하지 않는 학교입니다', 400: '잘못된 조건입니다. 학교와 말머리를 다시 골라 주세요' },
          '목록을 불러오지 못했습니다',
        )
        setResult({ key: queryKey, posts: [], error })
      })
    return () => {
      ignore = true
    }
  }, [schoolId, category, queryKey])

  // 저장된 결과가 지금 조건의 것이 아니면 아직 불러오는 중이다
  const loading = result?.key !== queryKey
  const posts = loading ? [] : (result?.posts ?? [])
  const error = loading ? null : (result?.error ?? null)

  // 드롭다운을 바꾸면 주소만 바꾼다. 그러면 위 useEffect가 알아서 다시 불러온다
  function changeFilter(name: 'schoolId' | 'category', value: string) {
    const next = new URLSearchParams(searchParams)
    if (value) next.set(name, value)
    else next.delete(name) // "전체"는 주소에서 뺀다
    setSearchParams(next)
  }

  // 주소의 값이 목록에 없으면 임시 option을 끼워 넣어, select가 주소를 그대로 보여 주게 한다
  // (안 그러면 "전체"로 보이는데 "전체"를 골라도 변화가 없어 빠져나올 수 없다)
  // 목록을 아직 받는 중이면 "알 수 없는"이 아니라 "불러오는 중..."으로 표시한다
  const schoolMissing = schoolId !== '' && !(schools ?? []).some((s) => String(s.id) === schoolId)
  const categoryMissing = category !== '' && !(categories ?? []).some((c) => c.code === category)
  const schoolsPending = schools === null && !optionsFailed
  const categoriesPending = categories === null && !optionsFailed

  return (
    <div>
      <h1>{SITE_NAME}</h1>
      {/* 회원 탈퇴 화면이 라우터 state로 넘긴 완료 문구 (없으면 안 보임) */}
      {notice && <Notice kind="info">{notice}</Notice>}
      {/* 글쓰기 버튼은 항상 보이되, 비로그인이면 로그인 화면으로 보낸다 */}
      <button onClick={() => navigate(user ? '/posts/new' : '/login')}>글쓰기</button>
      <div>
        <select aria-label="학교" value={schoolId} onChange={(e) => changeFilter('schoolId', e.target.value)}>
          <option value="">전체 학교</option>
          {schoolMissing && (
            <option value={schoolId}>{schoolsPending ? LOADING_TEXT : `알 수 없는 학교 (${schoolId})`}</option>
          )}
          {(schools ?? []).map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>{' '}
        <select aria-label="말머리" value={category} onChange={(e) => changeFilter('category', e.target.value)}>
          <option value="">전체 말머리</option>
          {categoryMissing && (
            <option value={category}>{categoriesPending ? LOADING_TEXT : `알 수 없는 말머리 (${category})`}</option>
          )}
          {(categories ?? []).map((c) => (
            <option key={c.code} value={c.code}>
              {c.name}
            </option>
          ))}
        </select>
        {optionsFailed && <p>학교·말머리 목록을 불러오지 못했습니다</p>}
      </div>
      {loading && <Loading />}
      {error && <Notice kind="error">{error}</Notice>}
      {!loading && !error && posts.length === 0 && <p>아직 글이 없습니다</p>}
      <ul>
        {posts.map((post) => (
          // key: 목록의 각 항목을 구분하는 고유값 (React 필수)
          <li key={post.id}>
            <small>[{post.category.name}]</small> <small>{post.school?.name ?? '학교 미지정'}</small>{' '}
            <Link to={`/posts/${post.id}`}>{post.title}</Link> <small>{post.author?.nickname ?? '(작성자 없음)'}</small>{' '}
            <small>{formatDate(post.createdAt)}</small>{' '}
            {/* 댓글 수·공감 수 */}
            <small>
              댓글 {post.commentCount} · 공감 {post.likeCount}
            </small>
          </li>
        ))}
      </ul>
    </div>
  )
}
