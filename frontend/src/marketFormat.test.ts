import { describe, it, expect } from 'vitest'
import { formatPrice } from './marketFormat'

// formatPrice: 목록·상세에 보이는 가격 글자가 그대로인지 고정한다
describe('formatPrice', () => {
  // 나눔은 가격이 0이어도 "0원"이 아니라 "나눔"으로 보여야 한다
  it('나눔은 "나눔"으로 표기한다', () => {
    expect(formatPrice({ tradeType: { code: 'FREE', name: '나눔' }, price: 0 })).toBe('나눔')
  })

  // 판매는 천 단위 쉼표 + "원"
  it('판매 5000원은 "5,000원"으로 표기한다', () => {
    expect(formatPrice({ tradeType: { code: 'SALE', name: '판매' }, price: 5000 })).toBe('5,000원')
  })

  // 허용 최대값(천만 원)에서도 쉼표가 두 번 들어가는지
  it('판매 10000000원은 "10,000,000원"으로 표기한다', () => {
    expect(formatPrice({ tradeType: { code: 'SALE', name: '판매' }, price: 10000000 })).toBe('10,000,000원')
  })
})
