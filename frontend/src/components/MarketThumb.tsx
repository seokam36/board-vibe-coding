import { useState } from 'react'
import { marketPhotoUrl } from '../marketPhotoUrl'
import type { MarketListItem } from '../types'

// 목록 행 왼쪽의 정사각 썸네일. 대표 사진이 없거나 불러오지 못하면 같은 크기의 회색 빈칸을 그린다
// (빈칸은 장식이라 스크린리더가 읽지 않게 aria-hidden)
export default function MarketThumb({ item }: { item: MarketListItem }) {
  // 사진 파일이 깨졌을 때(onError) true. 빈칸으로 바꿔 깨진 그림 아이콘이 안 보이게 한다
  const [broken, setBroken] = useState(false)
  const photo = item.coverPhoto
  if (!photo || broken) return <div className="market-thumb market-thumb-empty" aria-hidden="true" />
  return (
    <img
      className="market-thumb"
      src={marketPhotoUrl(item.id, photo.id, true)}
      width={photo.thumbWidth}
      height={photo.thumbHeight}
      alt={`${item.title} 대표 사진`}
      loading="lazy"
      onError={() => setBroken(true)}
    />
  )
}
