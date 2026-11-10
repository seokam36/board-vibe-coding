import { AxiosError } from 'axios'
import { describe, it, expect } from 'vitest'
import {
  CSRF_MESSAGE,
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
