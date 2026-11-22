// S27-a 단계 증가: 5 -> 10 -> 20 -> 40명, 단계당 1분. 앞뒤로 짧게 오르고 내린다.
// 왜: 어느 사용자 수부터 느려지는지 찾는다. 전후 비교 때 같은 값을 쓰려고 단계 값은 아래 한 곳에만 둔다.
import { BASE_URL, MAIN, pickPostIds, readRound, makeSummary, toSeconds, stageThresholds } from './lib/common.js';

// ===== 단계 값 (전후 비교 때 바꾸지 말 것) =====
// 한계를 찾을 때만 실행 때 STEPS 환경변수로 바꿔 넣는다(예: $env:STEPS="80,160,320"). 파일 기본값은 그대로 둔다.
const STEPS = __ENV.STEPS
  ? __ENV.STEPS.split(',').map((x) => {
      const n = Number(x.trim());
      if (!Number.isInteger(n) || n < 1 || n > 500) throw new Error(`STEPS는 1~500 사이 정수를 쉼표로: ${__ENV.STEPS}`);
      return n;
    })
  : [5, 10, 20, 40]; // 동시 사용자 수
const HOLD = '1m'; // 단계마다 유지 시간
const RAMP = '10s'; // 단계 사이 전환 시간
const WARM_UP = '15s'; // 처음 오르기
const COOL_DOWN = '30s'; // 마지막 내리기
// 총 약 5분 15초 (오르기 15s + 유지 4분 + 전환 30s + 내리기 30s)

const stages = [{ duration: WARM_UP, target: STEPS[0] }, { duration: HOLD, target: STEPS[0] }];
for (let i = 1; i < STEPS.length; i++) {
  stages.push({ duration: RAMP, target: STEPS[i] });
  stages.push({ duration: HOLD, target: STEPS[i] });
}
stages.push({ duration: COOL_DOWN, target: 0 });

// 단계별 숫자를 내려고, 각 단계의 "유지 구간"이 시작 몇 초부터 몇 초까지인지 미리 계산한다.
// 전환·오르기·내리기 구간은 'ramp'로 따로 묶어 단계 숫자에 섞이지 않게 한다.
const HOLD_SEC = toSeconds(HOLD);
const WINDOWS = [];
{
  let t = toSeconds(WARM_UP);
  STEPS.forEach((s, i) => {
    if (i > 0) t += toSeconds(RAMP);
    WINDOWS.push({ stage: String(s), from: t, to: t + HOLD_SEC });
    t += HOLD_SEC;
  });
}

export const options = {
  scenarios: {
    ramp: { executor: 'ramping-vus', startVUs: 0, stages, gracefulRampDown: '10s' },
  },
  // 자동 멈춤: 실패율 10% 이상 또는 p95 5초 이상이 20초 지켜본 뒤에도 이어지면 중단(서버를 더 괴롭히지 않으려고).
  // 준비 요청(phase:setup)은 빼고 본 측정 요청만 본다.
  thresholds: {
    [`http_req_failed${MAIN}`]: [{ threshold: 'rate<0.10', abortOnFail: true, delayAbortEval: '20s' }],
    [`http_req_duration${MAIN}`]: [{ threshold: 'p(95)<5000', abortOnFail: true, delayAbortEval: '20s' }],
    [`http_reqs${MAIN}`]: ['count>=0'], // 요약에 본 측정 요청 수를 남기려고(항상 통과)
    ...stageThresholds(STEPS),
  },
};

export function setup() {
  console.log(`대상: ${BASE_URL}`);
  // t0: 시나리오가 시작되는 시각. 단계 구간을 이 시각 기준으로 잰다.
  return { ids: pickPostIds(), t0: Date.now() };
}

export default function (data) {
  // 요청 직전 경과 시간으로 "지금 몇 명 단계 유지 구간인지" 고른다.
  const stageOf = () => {
    const sec = (Date.now() - data.t0) / 1000;
    const w = WINDOWS.find((x) => sec >= x.from && sec < x.to);
    return w ? w.stage : 'ramp';
  };
  readRound(data.ids, stageOf);
}

export function handleSummary(data) {
  return makeSummary('read-ramp', data, { steps: STEPS, holdSec: HOLD_SEC });
}
