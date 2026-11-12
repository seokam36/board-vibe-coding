import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ERROR_MESSAGES } from './errorMessage'
import {
  checkPhotoFiles,
  MAX_PHOTO_BYTES,
  PHOTO_UPLOAD_TIMEOUT_MESSAGE,
  uploadFailureText,
  uploadPhotosInOrder,
} from './marketPhotoUpload'

// api(axios)는 가짜로 바꾼다: post 호출 순서대로 미리 정한 결과를 돌려준다
const { post } = vi.hoisted(() => ({ post: vi.fn() }))
vi.mock('./api', () => ({ default: { post } }))

// 이름·종류·크기만 가진 가짜 파일 (내용은 중요하지 않다. 크기는 size로 따로 정한다)
function file(name: string, type: string, size = 100): File {
  const f = new File(['x'], name, { type })
  Object.defineProperty(f, 'size', { value: size })
  return f
}
const jpg = (name: string, size?: number) => file(name, 'image/jpeg', size)

describe('checkPhotoFiles', () => {
  it('종류·크기·장수가 섞이면 통과한 파일만 받고 이유를 한 줄로 합친다', () => {
    // 기존 8장 + 선택: 정상 3장(자리는 2장), gif 1장, 11MB 1장
    const ok1 = jpg('a.jpg')
    const ok2 = jpg('b.jpg')
    const ok3 = jpg('c.jpg')
    const gif = file('d.gif', 'image/gif')
    const big = jpg('e.jpg', MAX_PHOTO_BYTES + 1)

    const r = checkPhotoFiles([ok1, gif, big, ok2, ok3], 8)

    expect(r.accepted).toEqual([ok1, ok2])
    expect(r.message).toBe(
      'JPEG·PNG 사진만 올릴 수 있습니다. 1장은 받지 않았습니다. ' +
        '사진 한 장은 10MB 이하여야 합니다. 1장은 받지 않았습니다. ' +
        '사진은 10장까지 올릴 수 있습니다. 1장은 받지 않았습니다.',
    )
  })

  it('문제가 없으면 전부 받고 message는 null이다', () => {
    const files = [jpg('a.jpg'), file('b.png', 'image/png')]
    expect(checkPhotoFiles(files, 0)).toEqual({ accepted: files, message: null })
  })

  it('종류가 빈 문자열이면 통과시킨다 (서버가 내용으로 판단)', () => {
    const f = file('a.jfif', '')
    expect(checkPhotoFiles([f], 0)).toEqual({ accepted: [f], message: null })
  })

  it('image/jpg·image/pjpeg도 JPEG로 받는다', () => {
    const files = [file('a.jpg', 'image/jpg'), file('b.jpg', 'image/pjpeg')]
    expect(checkPhotoFiles(files, 0)).toEqual({ accepted: files, message: null })
  })

  it('gif는 거절하고 장수를 안내한다', () => {
    const r = checkPhotoFiles([file('a.gif', 'image/gif')], 0)
    expect(r.accepted).toEqual([])
    expect(r.message).toBe(ERROR_MESSAGES.photoSkipped(ERROR_MESSAGES.MARKET_PHOTO_TYPE_ONLY, 1))
  })

  it('정확히 10MB는 받고 1바이트 넘으면 거절한다', () => {
    const exact = jpg('a.jpg', MAX_PHOTO_BYTES)
    const over = jpg('b.jpg', MAX_PHOTO_BYTES + 1)
    expect(checkPhotoFiles([exact], 0)).toEqual({ accepted: [exact], message: null })
    const r = checkPhotoFiles([over], 0)
    expect(r.accepted).toEqual([])
    expect(r.message).toBe(ERROR_MESSAGES.photoSkipped(ERROR_MESSAGES.MARKET_PHOTO_TOO_LARGE, 1))
  })

  it('기존 장수와 선택을 합쳐 정확히 10장이면 모두 받는다', () => {
    const files = [jpg('a.jpg'), jpg('b.jpg')]
    expect(checkPhotoFiles(files, 8)).toEqual({ accepted: files, message: null })
  })

  it('합쳐서 11장이 되면 자리만큼만 받고 넘친 장수를 안내한다', () => {
    const files = [jpg('a.jpg'), jpg('b.jpg'), jpg('c.jpg')]
    const r = checkPhotoFiles(files, 8)
    expect(r.accepted).toEqual(files.slice(0, 2))
    expect(r.message).toBe(ERROR_MESSAGES.photoSkipped(ERROR_MESSAGES.MARKET_PHOTO_MAX_COUNT, 1))
  })

  it('이미 10장이면 하나도 받지 않는다', () => {
    const r = checkPhotoFiles([jpg('a.jpg')], 10)
    expect(r.accepted).toEqual([])
    expect(r.message).toBe(ERROR_MESSAGES.photoSkipped(ERROR_MESSAGES.MARKET_PHOTO_MAX_COUNT, 1))
  })
})

// 상태코드가 있는 axios 에러 / status가 없으면 응답 없는 네트워크 오류
function axiosError(status?: number): AxiosError {
  const e = new AxiosError('테스트용')
  if (status !== undefined) e.response = { status } as AxiosError['response']
  return e
}
const okPhoto = { id: 1, width: 1, height: 1, thumbWidth: 1, thumbHeight: 1, contentType: 'image/jpeg' }

describe('uploadFailureText', () => {
  const msg = (r: Parameters<typeof uploadFailureText>[0]) => uploadFailureText(r)

  it('1장 실패면 장수 접미사가 없다', () => {
    expect(msg({ failed: 1, firstError: axiosError(413) })).toEqual({ kind: 'message', message: '사진 한 장은 10MB 이하여야 합니다' })
  })

  it('2장 이상 실패면 " (n장 실패)"가 붙는다', () => {
    expect(msg({ failed: 2, firstError: axiosError(413) })).toEqual({
      kind: 'message',
      message: '사진 한 장은 10MB 이하여야 합니다 (2장 실패)',
    })
    expect(msg({ failed: 5, firstError: axiosError(503) })).toEqual({
      kind: 'message',
      message: '서버가 바쁩니다. 잠시 뒤 다시 시도해 주세요 (5장 실패)',
    })
  })

  it('firstError가 null이면 기본 문구를 쓴다', () => {
    expect(msg({ failed: 1, firstError: null })).toEqual({ kind: 'message', message: ERROR_MESSAGES.MARKET_PHOTO_UPLOAD_FAILED })
  })

  it('시간 초과(ECONNABORTED)면 시간 초과 문구를 쓰고 2장 이상이면 접미사가 붙는다', () => {
    const timeout = () => new AxiosError('timeout', 'ECONNABORTED')
    expect(PHOTO_UPLOAD_TIMEOUT_MESSAGE).toBe('시간이 오래 걸려 멈췄습니다. 새로고침해서 올라갔는지 확인해 주세요')
    expect(msg({ failed: 1, firstError: timeout() })).toEqual({ kind: 'message', message: PHOTO_UPLOAD_TIMEOUT_MESSAGE })
    expect(msg({ failed: 3, firstError: timeout() })).toEqual({
      kind: 'message',
      message: `${PHOTO_UPLOAD_TIMEOUT_MESSAGE} (3장 실패)`,
    })
  })

  it('일반 네트워크 오류(응답 없음, timeout 아님)는 기존 연결 안내 문구를 쓴다', () => {
    const r = msg({ failed: 1, firstError: axiosError() })
    expect(r).toEqual({ kind: 'message', message: '서버에 연결할 수 없습니다' })
    expect(r).not.toEqual({ kind: 'message', message: PHOTO_UPLOAD_TIMEOUT_MESSAGE })
  })
})

describe('uploadPhotosInOrder 중단 조건', () => {
  beforeEach(() => post.mockReset())

  // 첫 장이 실패했을 때 두 번째 장을 시도하는지 (true=계속, false=중단)
  const table: { name: string; err: () => AxiosError; continues: boolean }[] = [
    { name: '401', err: () => axiosError(401), continues: false },
    { name: '403', err: () => axiosError(403), continues: false },
    { name: '404', err: () => axiosError(404), continues: false },
    { name: '409', err: () => axiosError(409), continues: false },
    { name: '네트워크 오류(응답 없음)', err: () => axiosError(), continues: false },
    { name: '시간 초과(ECONNABORTED, 응답 없음)', err: () => new AxiosError('timeout', 'ECONNABORTED'), continues: false },
    { name: '400', err: () => axiosError(400), continues: true },
    { name: '413', err: () => axiosError(413), continues: true },
    { name: '503', err: () => axiosError(503), continues: true },
  ]

  for (const c of table) {
    it(`첫 장이 ${c.name}이면 다음 장을 ${c.continues ? '계속 올린다' : '시도하지 않고 전부 실패로 센다'}`, async () => {
      post.mockRejectedValueOnce(c.err())
      post.mockResolvedValue({ data: okPhoto })
      const uploaded: unknown[] = []

      const r = await uploadPhotosInOrder(5, [jpg('a.jpg'), jpg('b.jpg'), jpg('c.jpg')], () => {}, (p) => uploaded.push(p))

      if (c.continues) {
        expect(post).toHaveBeenCalledTimes(3)
        expect(r.failed).toBe(1)
        expect(uploaded).toHaveLength(2)
      } else {
        expect(post).toHaveBeenCalledTimes(1)
        expect(r.failed).toBe(3)
        expect(uploaded).toHaveLength(0)
      }
      expect(r.firstError).toBeInstanceOf(AxiosError)
    })
  }

  it('모두 성공하면 failed 0, firstError null이고 주소·timeout이 맞다', async () => {
    post.mockResolvedValue({ data: okPhoto })
    const r = await uploadPhotosInOrder(5, [jpg('a.jpg'), jpg('b.jpg')], () => {}, () => {})
    expect(r).toEqual({ failed: 0, firstError: null })
    expect(post).toHaveBeenCalledTimes(2)
    expect(post.mock.calls[0][0]).toBe('/market-items/5/photos')
    expect(post.mock.calls[0][2]).toEqual({ timeout: 300_000 })
  })

  it('isCancelled가 true가 되면 남은 장은 보내지 않고 실패로 센다', async () => {
    post.mockResolvedValue({ data: okPhoto })
    let cancelled = false
    const r = await uploadPhotosInOrder(
      5,
      [jpg('a.jpg'), jpg('b.jpg'), jpg('c.jpg')],
      () => {},
      () => {
        cancelled = true // 첫 장이 올라간 직후 화면을 떠난 상황
      },
      () => cancelled,
    )
    expect(post).toHaveBeenCalledTimes(1)
    expect(r.failed).toBe(2)
  })

  it('처음부터 취소 상태면 한 장도 보내지 않는다', async () => {
    const r = await uploadPhotosInOrder(5, [jpg('a.jpg'), jpg('b.jpg')], () => {}, () => {}, () => true)
    expect(post).not.toHaveBeenCalled()
    expect(r.failed).toBe(2)
  })
})
