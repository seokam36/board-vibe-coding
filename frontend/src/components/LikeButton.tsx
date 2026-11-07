import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import api from '../api'
import { NETWORK_ERROR_MESSAGE, getStatus, isNetworkError } from '../errorMessage'
import { useAuth } from '../auth/useAuth'
import ReloginNotice from './ReloginNotice'
import type { LikeResult } from '../types'

// 공감 버튼. 숫자는 글 응답의 likeCount로 시작하고, 누를 때마다 서버 응답의 값으로 맞춘다
export default function LikeButton({ postId, initialCount }: { postId: string; initialCount: number }) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [count, setCount] = useState(initialCount)
  // 누가 눌렀는지(uid)와 같이 저장해, 로그아웃/다른 계정이 되면 자동으로 '공감 안 함'으로 본다
  const [likedState, setLikedState] = useState<{ uid: number; liked: boolean } | null>(null)
  const liked = user !== null && likedState?.uid === user.id && likedState.liked
  const setLiked = (value: boolean) => {
    if (user) setLikedState({ uid: user.id, liked: value })
  }
  // 요청 중에는 버튼을 막아 연타를 방지한다
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const [needLogin, setNeedLogin] = useState(false)

  // 로그인했을 때만 "내가 눌렀는지"를 물어본다 (비로그인으로 부르면 401 → 재발급 시도가 헛돈다)
  const userId = user?.id
  useEffect(() => {
    if (userId === undefined) return
    let ignore = false
    api
      .get<{ liked: boolean }>(`/posts/${encodeURIComponent(postId)}/likes/me`)
      .then((res) => {
        if (!ignore) setLikedState({ uid: userId, liked: res.data.liked })
      })
      .catch(() => {
        // 확인에 실패해도 버튼은 쓸 수 있게 둔다. 누르면 서버가 409/404로 알려 준다
      })
    return () => {
      ignore = true
    }
  }, [postId, userId])

  async function handleClick() {
    // 비로그인이면 글쓰기 버튼처럼 로그인 화면으로 보낸다
    if (!user) {
      navigate('/login')
      return
    }
    setBusy(true)
    setMessage(null)
    setNeedLogin(false)
    const url = `/posts/${encodeURIComponent(postId)}/likes`
    try {
      // 지금 상태에 따라 공감(POST) 또는 취소(DELETE)
      const res = liked ? await api.delete<LikeResult>(url) : await api.post<LikeResult>(url)
      setCount(res.data.likeCount)
      setLiked(res.data.liked)
    } catch (err: unknown) {
      const status = getStatus(err)
      if (isNetworkError(err)) setMessage(NETWORK_ERROR_MESSAGE)
      else if (status === 401) setNeedLogin(true)
      else if (status === 409) {
        // 이미 눌려 있었음: 화면 상태만 '공감함'으로 맞춘다
        setLiked(true)
        setMessage('이미 공감한 글입니다')
      } else if (status === 404 && liked) {
        // 취소하려는데 눌러 둔 적이 없음: '공감 안 함'으로 맞춘다
        setLiked(false)
        setMessage('공감하지 않은 글입니다')
      } else if (status === 404) setMessage('글을 찾을 수 없습니다')
      else setMessage('처리하지 못했습니다')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div>
      {/* 글자는 "공감 N"으로 고정하고, 눌렀는지는 aria-pressed로 알린다 ("공감 취소 3"이 "3개 취소"처럼 읽히지 않게) */}
      <button
        type="button"
        onClick={handleClick}
        disabled={busy}
        aria-pressed={liked}
        aria-label={liked ? `공감 취소 (현재 ${count}명)` : `공감 (현재 ${count}명)`}
      >
        {liked ? '❤️' : '🤍'} 공감 {count}
      </button>
      {message && <p role="alert">{message}</p>}
      {needLogin && <ReloginNotice />}
    </div>
  )
}
