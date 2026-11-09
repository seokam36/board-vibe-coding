import Notice from './Notice'
import { HIKOREA_URL } from '../visaOfficialLink'

// 비자 후기 화면 위쪽 안내: 후기는 개인 경험이고, 공식 확인은 하이코리아에서 하라는 문구
export default function VisaDisclaimer() {
  return (
    <Notice kind="info">
      후기는 개인 경험이라 지금 기준과 다를 수 있어요(작성일을 확인하세요). 신청 대행·개인별 법률 상담은 하지 않아요. 공식 안내는{' '}
      {/* 새 탭으로 열되, noopener noreferrer로 열린 창이 이 사이트를 건드리지 못하게 한다 */}
      <a href={HIKOREA_URL} target="_blank" rel="noopener noreferrer">
        하이코리아
      </a>
      에서 확인하세요
    </Notice>
  )
}
