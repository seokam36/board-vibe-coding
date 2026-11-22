// S27-a 스모크: 가상 사용자 1명이 30초 동안 읽기 GET을 한 바퀴씩 돈다.
// 왜: 본격 부하 전에 "스크립트와 주소가 맞는지"만 가볍게 확인한다.
import { BASE_URL, MAIN, pickPostIds, readRound, makeSummary } from './lib/common.js';

export const options = {
  vus: 1,
  duration: '30s',
  // 자동 멈춤: 스모크는 기준을 빡빡하게(실패율 1% 미만, p95 1초 미만). 어기면 바로 중단.
  // 준비 요청(phase:setup)은 빼고 본 측정 요청만 본다.
  thresholds: {
    [`http_req_failed${MAIN}`]: [{ threshold: 'rate<0.01', abortOnFail: true, delayAbortEval: '5s' }],
    [`http_req_duration${MAIN}`]: [{ threshold: 'p(95)<1000', abortOnFail: true, delayAbortEval: '5s' }],
    [`http_reqs${MAIN}`]: ['count>=0'], // 요약에 본 측정 요청 수를 남기려고(항상 통과)
  },
};

export function setup() {
  console.log(`대상: ${BASE_URL}`);
  return { ids: pickPostIds() };
}

export default function (data) {
  readRound(data.ids);
}

export function handleSummary(data) {
  return makeSummary('read-smoke', data);
}
