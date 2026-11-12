import axios from 'axios'
import api from './api'
import { describeError, ERROR_MESSAGES, getStatus, isNetworkError } from './errorMessage'
import type { MessageTable } from './errorMessage'
import type { MarketItemPhoto } from './types'

// 사진 올리기 규칙 (docs/api.md 33번). 최종 판단은 서버이고, 브라우저는 바로 알려 주려고 미리 거른다
export const MAX_PHOTOS = 10
export const MAX_PHOTO_BYTES = 10 * 1024 * 1024
// 브라우저가 JPEG를 image/jpg·image/pjpeg로 알려 주는 경우도 있어 함께 받는다
const ALLOWED_TYPES = ['image/jpeg', 'image/jpg', 'image/pjpeg', 'image/png']
// 사진 한 장 올리기 제한 시간 (5분). 느린 회선에서 8~10MB가 정상 업로드되는 중에 끊지 않도록 넉넉히 둔다
const UPLOAD_TIMEOUT_MS = 300_000
// 시간 초과 전용 문구 (한 곳에서만 쓰는 지역 상수). 서버가 저장을 끝냈을 수도 있어 확인을 안내한다
export const PHOTO_UPLOAD_TIMEOUT_MESSAGE = '시간이 오래 걸려 멈췄습니다. 새로고침해서 올라갔는지 확인해 주세요'

// 올리기 실패 상태코드별 문구 (401은 표에 두지 않는다 -> describeError가 로그인 안내로 바꾼다)
export const PHOTO_UPLOAD_ERRORS: MessageTable = {
  400: ERROR_MESSAGES.MARKET_PHOTO_INVALID,
  403: ERROR_MESSAGES.MARKET_PHOTO_FORBIDDEN,
  404: ERROR_MESSAGES.POST_NOT_FOUND,
  409: ERROR_MESSAGES.MARKET_PHOTO_CONFLICT,
  413: ERROR_MESSAGES.MARKET_PHOTO_TOO_LARGE,
  503: ERROR_MESSAGES.MARKET_PHOTO_BUSY,
}

// 지우기 실패 상태코드별 문구
export const PHOTO_DELETE_ERRORS: MessageTable = {
  403: ERROR_MESSAGES.DELETE_RETRY_AFTER_RELOAD,
  404: ERROR_MESSAGES.alreadyDeleted('사진'),
  409: ERROR_MESSAGES.MARKET_PHOTO_DELETE_CONFLICT,
}

// 고른 파일을 서버에 보내기 전에 거른다. 받을 수 있는 것만 accepted에 담고, 뺀 이유는 message 한 줄로 합친다
// existing: 이미 올라가 있거나 미리보기로 담아 둔 사진 수
// 종류가 빈 문자열이면(브라우저가 모르는 확장자) 통과시킨다. 진짜 판단은 서버가 파일 내용(매직 바이트)으로 한다
export function checkPhotoFiles(files: File[], existing: number): { accepted: File[]; message: string | null } {
  const notes: string[] = []
  const typeOk = files.filter((f) => f.type === '' || ALLOWED_TYPES.includes(f.type))
  if (typeOk.length < files.length) {
    notes.push(ERROR_MESSAGES.photoSkipped(ERROR_MESSAGES.MARKET_PHOTO_TYPE_ONLY, files.length - typeOk.length))
  }
  const sizeOk = typeOk.filter((f) => f.size <= MAX_PHOTO_BYTES)
  if (sizeOk.length < typeOk.length) {
    notes.push(ERROR_MESSAGES.photoSkipped(ERROR_MESSAGES.MARKET_PHOTO_TOO_LARGE, typeOk.length - sizeOk.length))
  }
  const room = Math.max(0, MAX_PHOTOS - existing)
  const accepted = sizeOk.slice(0, room)
  if (accepted.length < sizeOk.length) {
    notes.push(ERROR_MESSAGES.photoSkipped(ERROR_MESSAGES.MARKET_PHOTO_MAX_COUNT, sizeOk.length - accepted.length))
  }
  return { accepted, message: notes.length > 0 ? notes.join(' ') : null }
}

// 제한 시간 초과로 끊긴 오류인지 (axios는 timeout을 code ECONNABORTED로 알린다)
function isTimeoutError(error: unknown): boolean {
  return axios.isAxiosError(error) && error.code === 'ECONNABORTED'
}

// 사진 한 장 올리기 (multipart, 파트 이름 file). CSRF 헤더는 api.ts의 axios가 붙인다
async function uploadOne(itemId: number, file: File): Promise<MarketItemPhoto> {
  const form = new FormData()
  form.append('file', file)
  // timeout: 응답이 없으면 무한정 기다리지 않는다. 시간이 지나면 ECONNABORTED 오류가 된다
  const res = await api.post<MarketItemPhoto>(`/market-items/${itemId}/photos`, form, { timeout: UPLOAD_TIMEOUT_MS })
  return res.data
}

export interface UploadResult {
  failed: number // 올리지 못한 장수 (중간에 멈췄으면 남은 것도 포함)
  firstError: unknown // 처음 난 에러 (없으면 null). 문구는 describeError로 만든다
}

// 사진을 한 장씩 차례로(동시에 아님) 올린다. 서버 이미지 처리 자리가 2개뿐이라 한꺼번에 보내지 않는다.
// 401(로그인 풀림)·403(권한/보안 토큰)·404(글 없음)·409(10장 초과/거래완료)·응답 없는 오류(서버 꺼짐·시간 초과)는
// 다음 장도 똑같이 실패하니 남은 것은 시도하지 않고 실패로 센다. 400·413·503은 그 사진만의 문제라 계속한다
// isCancelled: 화면을 떠났을 때 true를 돌려주면 남은 것은 보내지 않는다
export async function uploadPhotosInOrder(
  itemId: number,
  files: File[],
  onProgress: (current: number, total: number) => void,
  onUploaded: (photo: MarketItemPhoto) => void,
  isCancelled: () => boolean = () => false,
): Promise<UploadResult> {
  let failed = 0
  let firstError: unknown = null
  for (let i = 0; i < files.length; i++) {
    if (isCancelled()) {
      failed += files.length - i
      break
    }
    onProgress(i + 1, files.length)
    try {
      onUploaded(await uploadOne(itemId, files[i]))
    } catch (e: unknown) {
      failed++
      if (firstError === null) firstError = e
      const status = getStatus(e)
      if (status === 401 || status === 403 || status === 404 || status === 409 || isNetworkError(e)) {
        failed += files.length - i - 1
        break
      }
    }
  }
  return { failed, firstError }
}

// 여러 장이 실패했을 때 첫 에러 문구 뒤에 장수를 붙인다 (1장이면 문구 그대로)
export function uploadFailureText(result: UploadResult): { kind: 'needLogin' } | { kind: 'message'; message: string } {
  const info = describeError(result.firstError, PHOTO_UPLOAD_ERRORS, ERROR_MESSAGES.MARKET_PHOTO_UPLOAD_FAILED)
  if (info.kind === 'needLogin') return info
  // 시간 초과는 "연결 불가"가 아니라 따로 안내한다 (서버가 이미 저장했을 수 있어서)
  const base = isTimeoutError(result.firstError) ? PHOTO_UPLOAD_TIMEOUT_MESSAGE : info.message
  return { kind: 'message', message: result.failed > 1 ? `${base} (${result.failed}장 실패)` : base }
}
