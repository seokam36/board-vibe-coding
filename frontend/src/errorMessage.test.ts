import { AxiosError } from 'axios'
import { describe, it, expect } from 'vitest'
import {
  CSRF_MESSAGE,
  ERROR_MESSAGES,
  NETWORK_ERROR_MESSAGE,
  RELOGIN_MESSAGE,
  describeError,
  errorText,
} from './errorMessage'

// 진짜 AxiosError 객체를 만든다 (axios.isAxiosError가 true가 되게).
// status가 없으면 응답 자체가 없는 에러(서버 꺼짐 등)를 흉내 낸다.
function axiosError(status?: number): AxiosError {
  const error = new AxiosError('테스트용 에러')
  if (status !== undefined) {
    // response는 테스트에 필요한 status만 채운 가짜 응답
    error.response = { status } as AxiosError['response']
  }
  return error
}

describe('describeError', () => {
  it('응답 없는 axios 에러는 연결 불가 문구와 status undefined', () => {
    expect(describeError(axiosError(), {}, '기본')).toEqual({
      kind: 'message',
      message: NETWORK_ERROR_MESSAGE,
      status: undefined,
    })
  })

  it('401 + 빈 표는 needLogin', () => {
    expect(describeError(axiosError(401), {}, '기본')).toEqual({ kind: 'needLogin' })
  })

  it('401 + 표에 401 문구가 있으면 그 문구와 status 401', () => {
    expect(describeError(axiosError(401), { 401: '아이디 또는 비밀번호가 틀렸습니다' }, '기본')).toEqual({
      kind: 'message',
      message: '아이디 또는 비밀번호가 틀렸습니다',
      status: 401,
    })
  })

  it('404 + 표에 404 문구가 있으면 그 문구', () => {
    expect(describeError(axiosError(404), { 404: '글을 찾을 수 없습니다' }, '기본')).toEqual({
      kind: 'message',
      message: '글을 찾을 수 없습니다',
      status: 404,
    })
  })

  it('표에 없는 500은 기본 문구와 status 500', () => {
    expect(describeError(axiosError(500), { 404: '글을 찾을 수 없습니다' }, '기본')).toEqual({
      kind: 'message',
      message: '기본',
      status: 500,
    })
  })

  it('axios 에러가 아닌 일반 Error는 기본 문구와 status undefined', () => {
    expect(describeError(new Error('boom'), {}, '기본')).toEqual({
      kind: 'message',
      message: '기본',
      status: undefined,
    })
  })
})

describe('errorText', () => {
  it('401 + 빈 표는 needLogin 대신 기본 문구', () => {
    expect(errorText(axiosError(401), {}, '기본')).toBe('기본')
  })

  it('응답 없는 에러는 서버에 연결할 수 없다는 문구', () => {
    expect(errorText(axiosError(), {}, '기본')).toBe('서버에 연결할 수 없습니다')
  })

  // 표를 describeError에 제대로 넘기는지 확인 (UI3가 표를 옮길 때 끊기면 여기서 잡힌다)
  it('표에 있는 상태코드면 표의 문구', () => {
    expect(errorText(axiosError(404), { 404: '글을 찾을 수 없습니다' }, '기본')).toBe('글을 찾을 수 없습니다')
    expect(errorText(axiosError(401), { 401: '로그인이 필요합니다' }, '기본')).toBe('로그인이 필요합니다')
  })

  // 지금은 빈 문자열 문구를 "없는 것"으로 보고 기본 문구를 쓴다(|| 사용). ??로 바뀌면 여기서 잡힌다
  it('표의 문구가 빈 문자열이면 기본 문구', () => {
    expect(describeError(axiosError(401), { 401: '' }, '기본')).toEqual({ kind: 'message', message: '기본', status: 401 })
  })
})

describe('공용 문구 상수', () => {
  it('글자가 바뀌지 않는다', () => {
    expect(NETWORK_ERROR_MESSAGE).toBe('서버에 연결할 수 없습니다')
    expect(CSRF_MESSAGE).toBe('보안 토큰이 없습니다. 새로고침 후 다시 시도해 주세요')
    expect(RELOGIN_MESSAGE).toBe('다시 로그인해 주세요')
  })
})

// 문구 사전: 화면에 보이는 글자가 그대로인지 한 글자씩 고정한다 (함수는 예시 인자로)
describe('ERROR_MESSAGES 문구 사전', () => {
  it('글자가 바뀌지 않는다', () => {
    const { alreadyDeleted, ...texts } = ERROR_MESSAGES
    expect(texts).toEqual({
      POST_NOT_FOUND: '글을 찾을 수 없습니다',
      POST_LOAD_FAILED: '글을 불러오지 못했습니다',
      POST_FIELDS_INVALID: '제목, 내용, 말머리를 확인해 주세요 (제목은 최대 100자)',
      SAVE_FAILED: '저장하지 못했습니다',
      SCHOOL_NOT_FOUND: '존재하지 않는 학교입니다',
      SCHOOL_GATE_POST_ACTION:
        '학교 인증이 필요하거나 인증한 학교의 글이 아니에요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
      DELETE_RETRY_AFTER_RELOAD: '삭제하지 못했습니다. 새로고침 후 다시 시도해 주세요',
      DELETE_FORBIDDEN: '삭제할 권한이 없습니다',
      DELETE_FAILED: '삭제하지 못했습니다',
      POST_ALREADY_LIKED: '이미 공감한 글입니다',
      ACTION_FAILED: '처리하지 못했습니다',
      REVIEW_NOT_FOUND: '후기를 찾을 수 없습니다',
      REVIEW_LOAD_FAILED: '후기를 불러오지 못했습니다',
      REVIEW_FIELDS_INVALID: '날짜를 확인해 주세요 (접수일은 결과일과 같거나 앞서야 하고, 둘 다 오늘까지. 본문은 2000자까지)',
      REVIEW_FILTER_INVALID: '잘못된 조건입니다. 학교·비자 종류·신청 종류를 다시 골라 주세요',
      REVIEW_OPTIONS_LOAD_FAILED: '선택지 목록을 불러오지 못했습니다',
      VISA_SCHOOL_REQUIRED_RETRY: '학교 인증이 필요해요. 계속 안 되면 새로고침 후 다시 시도해 주세요',
    })
    expect(alreadyDeleted('글')).toBe('이미 삭제된 글입니다')
    expect(alreadyDeleted('댓글')).toBe('이미 삭제된 댓글입니다')
    expect(alreadyDeleted('후기')).toBe('이미 삭제된 후기입니다')
    expect(alreadyDeleted('일정')).toBe('이미 삭제된 일정입니다')
  })
})
