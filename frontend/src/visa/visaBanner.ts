// 상단 비자 일정 띠의 문구·닫기 키를 정하는 순수 함수 모음 (화면과 분리해 단위 테스트한다)
import { formatDDay } from '../dday'
import type { VisaPlan } from '../types'

// 띠가 뜨는 단계 (stage가 null이면 띠 없음)
export type VisaStage = NonNullable<VisaPlan['stage']>

// ---- 띠 문구: 나중에 문구를 고칠 때는 여기만 바꾼다. {D}는 formatDDay 결과(D-30 등)로 바뀐다 ----
export const BANNER_TEXT: Record<VisaStage, string> = {
  PREPARE: '체류 만료 {D} — 연장·자격 변경 준비를 확인하세요',
  CHECK: '체류 만료 {D} — 신청 방법을 확인하세요',
  URGENT: '체류 만료 {D} — 체류 상태를 확인하세요',
  EXPIRED: '등록한 체류 만료일이 지났습니다 — 날짜를 새로 등록하거나 지워 주세요',
}
export const BANNER_LINK_TEXT = '비자 일정 보기'
export const BANNER_CLOSE_LABEL = '띠 닫기'

// 단계와 남은 일수로 띠 문구를 만든다
export function bannerMessage(stage: VisaStage, daysLeft: number): string {
  return BANNER_TEXT[stage].replace('{D}', formatDDay(daysLeft))
}

// 닫은 상태를 저장하는 localStorage 키: 회원당 하나. 값을 덮어쓰니 키가 쌓이지 않는다
export function bannerDismissKey(userId: number): string {
  return `visaBannerClosed:${userId}`
}

// 만료일을 알아볼 수 없게 바꾸는 짧은 비암호 해시 (djb2 32비트 → 36진수). 공용 PC에서 만료일이 보이지 않게 하려는 용도
export function shortHash(text: string): string {
  let h = 5381
  for (let i = 0; i < text.length; i++) h = ((h * 33) ^ text.charCodeAt(i)) >>> 0
  return h.toString(36)
}

// 닫은 값: 단계 + 만료일 해시. 단계가 바뀌거나 만료일을 바꾸면 값이 달라져 다시 뜬다
export function bannerDismissValue(expiryDate: string, stage: VisaStage): string {
  return `${stage}:${shortHash(expiryDate)}`
}

// 저장된 값이 지금 값과 같으면 닫힌 상태. 저장소가 막힌 브라우저에서는 예외가 나도 "안 닫힘"으로 본다
export function isBannerDismissed(userId: number, value: string): boolean {
  try {
    return localStorage.getItem(bannerDismissKey(userId)) === value
  } catch {
    return false
  }
}

// 닫은 값을 저장한다(덮어쓰기). 막힌 브라우저에서는 조용히 넘어간다 (이번 화면에서만 닫힘)
export function saveBannerDismissed(userId: number, value: string): void {
  try {
    localStorage.setItem(bannerDismissKey(userId), value)
  } catch {
    // 저장 실패는 무시
  }
}
