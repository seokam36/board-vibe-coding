import { useEffect, useRef, useState } from 'react'
import api from '../api'
import { describeError, ERROR_MESSAGES } from '../errorMessage'
import { marketPhotoUrl } from '../marketPhotoUrl'
import { PHOTO_DELETE_ERRORS, uploadFailureText, uploadPhotosInOrder } from '../marketPhotoUpload'
import { useConfirm } from './confirmContext'
import MarketPhotoPicker from './MarketPhotoPicker'
import type { MarketItemPhoto } from '../types'

interface Props {
  itemId: number
  initial: MarketItemPhoto[]
  onBusyChange?: (busy: boolean) => void // 올리는 중·지우는 중인지 부모에게 알린다 (글자 [저장] 버튼 잠금용)
  disabled?: boolean // 부모가 밖에서 잠그는 값 (글자 저장 중이면 사진 추가·삭제도 잠근다)
}

// 수정 화면의 사진 영역. 사진은 [저장] 버튼과 상관없이 올리거나 지우는 즉시 서버에 반영된다
export default function MarketPhotoEditor({ itemId, initial, onBusyChange, disabled = false }: Props) {
  const [photos, setPhotos] = useState(initial)
  const [busy, setBusy] = useState(false) // 올리는 중·지우는 중 (버튼 잠금)
  const busyRef = useRef(false) // 같은 순간에 두 번 실행되는 것을 막는 즉시 표시 (state는 다음 그림에야 바뀐다)
  const mounted = useRef(true) // 화면을 떠났으면 남은 사진을 더 올리지 않는다
  const [progress, setProgress] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [needLogin, setNeedLogin] = useState(false)
  const confirm = useConfirm() // 사진 삭제 전 확인 창 (다른 삭제와 같은 방식)

  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])

  useEffect(() => {
    onBusyChange?.(busy)
  }, [busy, onBusyChange])

  async function handleAdd(files: File[]) {
    if (busyRef.current) return
    busyRef.current = true
    setBusy(true)
    setError(null)
    setNeedLogin(false)
    try {
      // 성공한 사진은 올라오는 대로 목록에 붙인다
      const result = await uploadPhotosInOrder(
        itemId,
        files,
        (cur, total) => setProgress(`사진 올리는 중 ${cur}/${total}`),
        (photo) => setPhotos((prev) => [...prev, photo]),
        () => !mounted.current,
      )
      if (result.failed > 0) {
        const info = uploadFailureText(result)
        if (info.kind === 'needLogin') setNeedLogin(true)
        else setError(info.message)
      }
    } finally {
      // 예외가 나도 잠금이 풀리게 한다
      setProgress(null)
      busyRef.current = false
      setBusy(false)
    }
  }

  async function handleRemove(key: number | string) {
    if (busyRef.current) return
    const index = photos.findIndex((p) => p.id === key)
    if (index < 0) return
    busyRef.current = true // 확인 창이 떠 있는 동안에도 다른 작업이 끼어들지 못하게 먼저 잠근다
    if (!(await confirm({ message: `사진 ${index + 1}번을 삭제할까요?`, confirmLabel: '삭제', danger: true }))) {
      busyRef.current = false
      return
    }
    setBusy(true)
    setError(null)
    setNeedLogin(false)
    try {
      await api.delete(`/market-items/${itemId}/photos/${key}`)
      setPhotos((prev) => prev.filter((p) => p.id !== key))
    } catch (e: unknown) {
      const info = describeError(e, PHOTO_DELETE_ERRORS, ERROR_MESSAGES.DELETE_FAILED)
      if (info.kind === 'needLogin') setNeedLogin(true)
      else {
        setError(info.message)
        // 이미 지워진 사진(404)은 목록에서도 뺀다
        if (info.status === 404) setPhotos((prev) => prev.filter((p) => p.id !== key))
      }
    } finally {
      busyRef.current = false
      setBusy(false)
    }
  }

  return (
    <MarketPhotoPicker
      items={photos.map((p) => ({ key: p.id, src: marketPhotoUrl(itemId, p.id, true) }))}
      disabled={busy || disabled}
      progress={progress}
      error={error}
      needLogin={needLogin}
      removeLabel="삭제"
      onAdd={handleAdd}
      onRemove={handleRemove}
    />
  )
}
