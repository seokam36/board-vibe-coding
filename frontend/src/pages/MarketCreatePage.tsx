import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router'
import api from '../api'
import { ERROR_MESSAGES } from '../errorMessage'
import { uploadPhotosInOrder } from '../marketPhotoUpload'
import MarketItemForm from '../components/MarketItemForm'
import MarketPhotoPicker from '../components/MarketPhotoPicker'
import MarketWriteGate from '../components/MarketWriteGate'
import { useAuth } from '../auth/useAuth'
import type { MarketItem, MarketItemRequest } from '../types'

// 중고·나눔 글쓰기. 학교 인증을 마친 회원만 쓸 수 있다 (학교는 서버가 인증 학교로 정한다)
export default function MarketCreatePage() {
  const { user } = useAuth()
  // 저장 중 로그인이 풀려 user가 null이 돼도 폼(입력값)을 지키려고, 인증 회원이었음을 기억해 둔다
  const [wasVerified, setWasVerified] = useState(false)
  const verified = !!user?.verifiedSchool
  if (verified && !wasVerified) setWasVerified(true) // 렌더 중 갱신(허용되는 패턴)
  const canWrite = user ? verified : wasVerified
  const navigate = useNavigate()

  // 고른 사진: 서버에는 [저장]을 누를 때 올리고, 그 전에는 브라우저 미리보기(blob 주소)로만 보인다
  const [picked, setPicked] = useState<{ key: number; file: File; url: string }[]>([])
  const nextKey = useRef(0)
  const urls = useRef(new Set<string>()) // 만든 미리보기 주소들. 화면을 떠날 때 한꺼번에 반납(revoke)한다
  const [saving, setSaving] = useState(false) // 글 만들기 + 사진 올리기 전체 진행 중 (사진 버튼 잠금)
  const [progress, setProgress] = useState<string | null>(null)

  const mounted = useRef(true) // 올리는 도중 화면을 떠났는지 (떠났으면 남은 올리기·이동을 건너뛴다)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    const set = urls.current
    return () => {
      set.forEach((u) => URL.revokeObjectURL(u))
      set.clear()
    }
  }, [])

  function handleAdd(files: File[]) {
    const added = files.map((file) => {
      const url = URL.createObjectURL(file)
      urls.current.add(url)
      return { key: nextKey.current++, file, url }
    })
    setPicked((prev) => [...prev, ...added])
  }

  function handleRemove(key: number | string) {
    const target = picked.find((p) => p.key === key)
    if (target) {
      URL.revokeObjectURL(target.url)
      urls.current.delete(target.url)
    }
    setPicked((prev) => prev.filter((p) => p.key !== key))
  }

  async function save(body: MarketItemRequest) {
    setSaving(true)
    let created: MarketItem
    try {
      created = (await api.post<MarketItem>('/market-items', body)).data
    } catch (e) {
      setSaving(false) // 글 만들기가 실패하면 사진은 올리지 않고 폼에 에러를 보여 준다
      throw e
    }
    // 글은 만들어졌으니 사진을 차례로 올린다. 일부가 실패해도 상세로 가서 안내한다
    const result = await uploadPhotosInOrder(
      created.id,
      picked.map((p) => p.file),
      (cur, total) => setProgress(`사진 올리는 중 ${cur}/${total}`),
      () => {},
      () => !mounted.current,
    )
    if (!mounted.current) return // 화면을 떠났으면 이동하지 않는다
    // 실패 장수는 router state로 상세 화면에 넘긴다 (상세 화면이 읽자마자 기록에서 지운다)
    navigate(`/market/${created.id}`, { state: result.failed > 0 ? { photoFailed: result.failed } : null })
  }

  if (!canWrite) {
    return (
      <div>
        <h1>중고·나눔 글쓰기</h1>
        <MarketWriteGate user={user} />
      </div>
    )
  }

  return (
    <div>
      <h1>중고·나눔 글쓰기</h1>
      <MarketPhotoPicker
        items={picked.map((p) => ({ key: p.key, src: p.url }))}
        disabled={saving}
        progress={progress}
        error={null}
        needLogin={false}
        removeLabel="빼기"
        onAdd={handleAdd}
        onRemove={handleRemove}
      />
      <MarketItemForm
        onSave={save}
        errorTable={{
          400: ERROR_MESSAGES.MARKET_FIELDS_INVALID,
          403: ERROR_MESSAGES.VISA_SCHOOL_REQUIRED_RETRY,
        }}
        cancelTo="/market"
      />
    </div>
  )
}
