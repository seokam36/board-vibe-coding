import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import api from '../api'
import { describeError, errorText, ERROR_MESSAGES } from '../errorMessage'
import { isAdmin } from '../auth/isAdmin'
import { canActInSchool } from '../auth/canActInSchool'
import { useAuth } from '../auth/useAuth'
import { useConfirm } from '../components/confirmContext'
import ReportForm from '../components/ReportForm'
import Loading from '../components/Loading'
import Notice from '../components/Notice'
import ReloginNotice from '../components/ReloginNotice'
import { formatDate } from '../formatDate'
import { formatPrice } from '../marketFormat'
import type { MarketItem } from '../types'

type Status = MarketItem['status']['code']

// 지금 상태에서 갈 수 있는 상태 (서버 규칙과 같다: DONE에서는 나갈 수 없다). 화면은 이 표에 있는 버튼만 보여 준다
// done: 성공했을 때 알려 주는 한 줄 (이 화면에서만 쓰는 문구라 ERROR_MESSAGES가 아니라 여기에 둔다)
const NEXT_STATUS: Record<Status, { to: Status; label: string; done: string }[]> = {
  ON_SALE: [
    { to: 'RESERVED', label: '예약중으로', done: '예약중으로 바꿨습니다' },
    { to: 'DONE', label: '거래 완료', done: '거래 완료로 바꿨습니다' },
  ],
  RESERVED: [
    { to: 'ON_SALE', label: '판매중으로', done: '판매중으로 바꿨습니다' },
    { to: 'DONE', label: '거래 완료', done: '거래 완료로 바꿨습니다' },
  ],
  DONE: [],
}

// 서버만 먼저 배포돼 프론트가 모르는 상태가 오면 표에 없다. 그때는 빈 목록(버튼 없음)으로 읽어 화면이 죽지 않게 한다
// (Record<string, ...>로 넓혀 읽는 방식은 visaBanner.ts와 같다)
function nextStatusesOf(code: string) {
  return (NEXT_STATUS as Record<string, (typeof NEXT_STATUS)[Status] | undefined>)[code] ?? []
}

// 중고거래·나눔 글 상세. 판매자 본인은 상태 바꾸기·수정·삭제, 관리자는 삭제만 쓸 수 있다
export default function MarketDetailPage() {
  // useParams: 주소의 :id 부분. 항상 문자열(또는 undefined)이다
  const { id } = useParams()
  const safeId = encodeURIComponent(id ?? '') // '?' 같은 문자가 경로에 섞여 목록 API로 바뀌는 것을 막는다
  const [item, setItem] = useState<MarketItem | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  // 버튼 요청 중이면 'status' 또는 'delete'. 값이 있는 동안 모든 버튼을 잠가 두 번 눌림을 막는다
  const [busy, setBusy] = useState<'status' | 'delete' | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  // 상태 바꾸기에 성공했을 때 버튼 근처에 띄우는 한 줄 (누른 버튼이 사라져도 스크린리더가 결과를 듣게 한다)
  const [statusNotice, setStatusNotice] = useState<string | null>(null)
  const [needLogin, setNeedLogin] = useState(false) // 요청 중 401(재발급 실패) 로그인 안내
  const navigate = useNavigate()
  const { user } = useAuth()
  const confirm = useConfirm() // 거래 완료·삭제 전 확인 창 (비자 후기 삭제와 같은 방식)
  const isMine = !!user && item?.seller.id === user.id // 내 글인지 (판매자 본인)
  const canAct = canActInSchool(user, item?.school.id) // 신고는 인증한 학교의 글에만 (서버 규칙과 같다)

  useEffect(() => {
    // 응답이 오기 전에 화면이 사라지거나 id가 바뀌면 늦게 온 응답을 버리기 위한 플래그
    let ignore = false
    api
      .get<MarketItem>(`/market-items/${safeId}`)
      .then((res) => {
        if (ignore) return
        setItem(res.data)
        setError(null)
      })
      .catch((e: unknown) => {
        if (ignore) return
        setItem(null)
        setError(
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

  // 상태 바꾸기: 성공하면 서버가 돌려준 글로 화면을 바꾼다
  async function changeStatus(to: Status, doneText: string) {
    // 거래 완료는 되돌릴 수 없으니 먼저 확인한다. 취소하면 아무것도 하지 않는다
    if (
      to === 'DONE' &&
      !(await confirm({ message: '거래 완료로 바꾸면 되돌릴 수 없습니다. 완료할까요?', confirmLabel: '거래 완료', danger: true }))
    )
      return
    setBusy('status')
    setActionError(null)
    setStatusNotice(null) // 이전 성공 안내는 새 요청을 시작하면 지운다
    setNeedLogin(false)
    try {
      const res = await api.put<MarketItem>(`/market-items/${safeId}/status`, { status: to })
      setItem(res.data)
      setStatusNotice(doneText)
    } catch (err: unknown) {
      const info = describeError(
        err,
        {
          403: ERROR_MESSAGES.MARKET_STATUS_RETRY, // 본인 글인데 403이면 보안 토큰 문제일 수 있다
          404: ERROR_MESSAGES.alreadyDeleted('글'),
          409: ERROR_MESSAGES.MARKET_STATUS_CONFLICT,
        },
        ERROR_MESSAGES.MARKET_STATUS_FAILED,
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setActionError(info.message)
    } finally {
      setBusy(null) // 성공해도 화면에 남으니 항상 버튼을 되살린다
    }
  }

  async function handleDelete() {
    if (!(await confirm({ message: '이 글을 삭제할까요?', confirmLabel: '삭제', danger: true }))) return
    setBusy('delete')
    setActionError(null)
    setStatusNotice(null)
    setNeedLogin(false)
    try {
      await api.delete(`/market-items/${safeId}`)
      // replace: 삭제된 글의 상세 화면이 뒤로가기 기록에 남지 않게 한다
      navigate('/market', { replace: true })
    } catch (err: unknown) {
      const info = describeError(
        err,
        {
          403: isMine ? ERROR_MESSAGES.DELETE_RETRY_AFTER_RELOAD : ERROR_MESSAGES.DELETE_FORBIDDEN,
          404: ERROR_MESSAGES.alreadyDeleted('글'),
        },
        ERROR_MESSAGES.DELETE_FAILED,
      )
      if (info.kind === 'needLogin') setNeedLogin(true)
      else setActionError(info.message)
      setBusy(null) // 실패했을 때만 버튼을 되살린다 (성공하면 페이지가 바뀐다)
    }
  }

  const locked = busy !== null
  const nextStatuses = item ? nextStatusesOf(item.status.code) : []

  return (
    <div>
      {loading && <Loading />}
      {error && <Notice kind="error">{error}</Notice>}
      {item && (
        <article className="panel">
          <div className="badges">
            <span className={`badge badge-${item.status.code}`}>{item.status.name}</span>
            <span className="badge badge-school">{item.school.name}</span>
          </div>
          <h1 className="post-title">{item.title}</h1>
          <p className="meta">
            <span>{item.seller.nickname}</span>
            <span>{formatDate(item.createdAt)}</span>
          </p>
          <p className="market-price">{formatPrice(item)}</p>
          {/* pre-wrap: 줄바꿈·공백을 그대로 보여준다. 텍스트로만 출력하므로 HTML이 실행되지 않는다 */}
          <p className="pre-wrap post-body">{item.content}</p>
        </article>
      )}
      {/* 버튼 표시 규칙(숨기는 건 편의일 뿐, 진짜 보호는 서버의 403):
          판매자 본인 = 상태 버튼(갈 수 있는 것만) + 수정(거래완료 아닐 때) + 삭제 / 관리자(남의 글) = 삭제만 */}
      {item && user && (isMine || isAdmin(user)) && (
        <div className="actions">
          {isMine &&
            nextStatuses.map((s) => (
              <button key={s.to} type="button" className="btn btn-sm" onClick={() => changeStatus(s.to, s.done)} disabled={locked}>
                {busy === 'status' ? '처리 중...' : s.label}
              </button>
            ))}
          {isMine &&
            item.status.code !== 'DONE' &&
            // 요청 중에는 링크를 못 막으니 비활성 버튼 모양으로 바꾼다
            (locked ? (
              <button type="button" className="btn btn-sm" disabled>
                수정
              </button>
            ) : (
              <Link to={`/market/${safeId}/edit`} className="btn btn-sm">
                수정
              </Link>
            ))}
          <button type="button" className="btn btn-sm btn-danger" onClick={handleDelete} disabled={locked}>
            {busy === 'delete' ? '삭제 중...' : '삭제'}
          </button>
        </div>
      )}
      {/* 신고는 로그인했고 내 글이 아니고 인증한 학교의 글일 때만 (PostDetailPage와 같은 조건) */}
      {item && user && !isMine && canAct && (
        <div className="actions">
          <ReportForm
            url={`/market-items/${safeId}/reports`}
            label="중고 글"
            onNeedLogin={() => {
              setActionError(null)
              setNeedLogin(true) // 로그인 안내는 아래 user 조건 밖에서 보여 준다
            }}
          />
        </div>
      )}
      {/* 실패 안내는 버튼 영역 밖에 둔다(로그인이 풀려 버튼이 사라져도 안내가 남게) */}
      {/* 성공 안내(role="status"): 버튼이 사라져도 결과를 알려 준다. 에러가 나면 위 setStatusNotice(null)로 이미 지워져 있다 */}
      {statusNotice && <Notice kind="success">{statusNotice}</Notice>}
      {actionError && <Notice kind="error">{actionError}</Notice>}
      {needLogin && <ReloginNotice />}
      <p>
        <Link to="/market" className="btn">
          목록으로
        </Link>
      </p>
    </div>
  )
}
