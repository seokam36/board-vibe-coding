import type { MarketItem } from './types'

// 가격 표기: 나눔은 "나눔", 판매는 천 단위 쉼표를 넣은 "5,000원"
export function formatPrice(item: Pick<MarketItem, 'tradeType' | 'price'>): string {
  if (item.tradeType.code === 'FREE') return '나눔'
  return `${item.price.toLocaleString('ko-KR')}원`
}
