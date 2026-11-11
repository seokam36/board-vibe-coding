import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { errorText, ERROR_MESSAGES } from '../errorMessage'
import Loading from '../components/Loading'
import MarketItemForm from '../components/MarketItemForm'
import Notice from '../components/Notice'
import { useAuth } from '../auth/useAuth'
import type { MarketItem, MarketItemRequest } from '../types'

// 중고·나눔 글 수정: 기존 글로 채운 같은 폼. 판매자 본인만, 거래완료(DONE) 글은 수정 불가
export default function MarketEditPage() {
  const { id } = useParams()
  const safeId = encodeURIComponent(id ?? '') // 상세 화면과 같은 이유로 인코딩
  const [item, setItem] = useState<MarketItem | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const { user } = useAuth()
  const navigate = useNavigate()

  useEffect(() => {
    // 늦게 온 응답으로 상태를 바꾸지 않기 위한 플래그
    let ignore = false
    api
      .get<MarketItem>(`/market-items/${safeId}`)
      .then((res) => {
        if (ignore) return
        setItem(res.data)
        setLoadError(null)
      })
      .catch((e: unknown) => {
        if (ignore) return
        setItem(null)
        setLoadError(
          errorText(
            e,
            { 400: ERROR_MESSAGES.POST_NOT_FOUND, 404: ERROR_MESSAGES.POST_NOT_FOUND },
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
  }, [safeId])

  async function save(body: MarketItemRequest) {
    await api.put(`/market-items/${safeId}`, body)
    // replace: 저장한 수정 폼이 뒤로가기 기록에 남지 않게 한다
    navigate(`/market/${safeId}`, { replace: true })
  }

  // 지금 주소의 글일 때만 쓴다 (숫자로 비교해 /market/01 처럼 서버가 같은 글로 보는 주소도 통과)
  const loaded = item && item.id === Number(id) ? item : null
  // 로그인했는데 남의 글이면 폼 대신 안내. (user가 null = 저장 중 세션 만료면 폼을 유지해 ReloginNotice가 보이게 한다)
  const notMine = !!loaded && !!user && loaded.seller.id !== user.id
  const done = loaded?.status.code === 'DONE'

  return (
    <div>
      <h1>중고·나눔 글 수정</h1>
      {loading && <Loading />}
      {loadError && <Notice kind="error">{loadError}</Notice>}
      {notMine && <Notice kind="error">본인 글만 수정할 수 있습니다</Notice>}
      {loaded && !notMine && done && <Notice kind="error">{ERROR_MESSAGES.MARKET_DONE_NOT_EDITABLE}</Notice>}
      {loaded && !notMine && !done && (
        <MarketItemForm
          key={loaded.id}
          initial={{
            title: loaded.title,
            content: loaded.content,
            tradeType: loaded.tradeType.code,
            price: loaded.price,
          }}
          onSave={save}
          errorTable={{
            400: ERROR_MESSAGES.MARKET_FIELDS_INVALID,
            // 본인 글인데 403이면 CSRF 토큰 오류일 수 있다 (비자 후기 수정과 같은 방식)
            403: '저장하지 못했습니다. 새로고침 후 다시 시도해 주세요',
            404: ERROR_MESSAGES.POST_NOT_FOUND,
            409: ERROR_MESSAGES.MARKET_DONE_NOT_EDITABLE,
          }}
          cancelTo={`/market/${safeId}`}
        />
      )}
      {(loadError || notMine || done) && (
        <p>
          <Link to="/market" className="btn">
            목록으로
          </Link>
        </p>
      )}
    </div>
  )
}
