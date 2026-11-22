// 부하 테스트 공통 부품 (스모크·단계 증가 스크립트가 같이 쓴다).
// 안전 원칙: 읽기 GET만 보낸다. 쓰기 호출·챗봇·허가서·사진 주소는 이 폴더에 아예 없다.
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';

// 사이트 주소는 코드에 쓰지 않고 환경변수로만 받는다.
// BASE_URL이 없거나 이상하면 init 단계에서 throw -> 요청을 하나도 보내지 않고 0이 아닌 코드로 끝난다.
// 운영은 nginx 뒤라 /api까지 넣는다: https://<사이트주소>/api, 로컬 bootRun은 http://localhost:8080
const RAW_BASE = (__ENV.BASE_URL || '').trim();
if (!/^https?:\/\/[^/]/.test(RAW_BASE)) {
  throw new Error(
    'BASE_URL이 없거나 형식이 틀렸어. 요청은 하나도 보내지 않았어.\n' +
      '  로컬: $env:BASE_URL="http://localhost:8080"\n' +
      '  운영: $env:BASE_URL="https://<사이트주소>/api"  (운영은 사용자 승인 후에만)\n' +
      '  끝에 / 는 붙이지 마.'
  );
}
export const BASE_URL = RAW_BASE.replace(/\/+$/, '');

// 화면 첫 파일(/)은 운영 nginx에서만 의미가 있어서, WEB_URL이 있을 때만 부른다.
export const WEB_URL = (__ENV.WEB_URL || '').trim().replace(/\/+$/, '');

// 일반적인 이름만 쓴다(개인정보 금지).
const PARAMS = { headers: { 'User-Agent': 'board-loadtest' } };

// 본 측정 요청에는 phase:main 꼬리표를 단다.
// 왜: 시작할 때 글 id를 뽑는 준비 요청(phase:setup)이 자동 멈춤 기준과 결과 숫자에 섞이지 않게 하려고.
export const MAIN = '{phase:main}';

// 요청마다 name 태그를 달아, 결과에서 API별로 나눠 볼 수 있게 한다(글 id가 달라도 한 묶음).
// stage는 단계 증가 스크립트가 "지금 몇 명 단계인지" 알려 줄 때만 붙는다(전환 구간은 'ramp').
function get(url, name, stage) {
  const tags = { name, phase: 'main' };
  if (stage) tags.stage = stage;
  const res = http.get(url, { ...PARAMS, tags });
  check(res, { [`${name} 200`]: (r) => r.status === 200 });
  return res;
}

// 시작할 때 딱 한 번: 글 목록에서 앞쪽 id 몇 개를 뽑아 보조 요청(글 하나·댓글)에 쓴다.
export function pickPostIds() {
  const res = http.get(`${BASE_URL}/posts`, { ...PARAMS, tags: { name: 'setup GET /posts', phase: 'setup' } });
  if (res.status !== 200) return [];
  try {
    return res.json().slice(0, 3).map((p) => p.id);
  } catch (e) {
    return [];
  }
}

// 한 바퀴: 읽기 GET만 차례로 부르고, 요청 사이 1초 쉰다.
// stageOf: 요청 직전에 "지금 단계 이름"을 돌려주는 함수(스모크는 안 넘김).
export function readRound(ids, stageOf) {
  const st = () => (stageOf ? stageOf() : undefined);
  get(`${BASE_URL}/posts`, 'GET /posts', st()); // 주력
  sleep(1);
  // 글이 없으면 보조 요청(글 하나·댓글)은 건너뛴다. 돌 때마다 다른 id를 쓴다.
  if (ids.length > 0) {
    const id = ids[exec.vu.iterationInScenario % ids.length];
    get(`${BASE_URL}/posts/${id}`, 'GET /posts/{id}', st());
    sleep(1);
    get(`${BASE_URL}/posts/${id}/comments`, 'GET /posts/{id}/comments', st());
    sleep(1);
  }
  get(`${BASE_URL}/market-items`, 'GET /market-items', st());
  sleep(1);
  get(`${BASE_URL}/visa-reviews/stats`, 'GET /visa-reviews/stats', st());
  sleep(1);
  if (WEB_URL) {
    get(`${WEB_URL}/`, 'GET / (web)', st());
    sleep(1);
  }
}

// '1m', '10s' 같은 시간 글자를 초로 바꾼다(단계 구간 계산용).
export function toSeconds(d) {
  const m = /^(\d+)(s|m)$/.exec(d);
  if (!m) throw new Error(`시간 형식은 '10s'나 '1m'만 써: ${d}`);
  return Number(m[1]) * (m[2] === 'm' ? 60 : 1);
}

// 결과 요약에 단계별 숫자가 나오게 하려면 꼬리표 묶음(하위 지표)을 thresholds에 적어 둬야 한다.
// 항상 통과하는 기준(>=0)이라 멈춤에는 영향이 없고, 숫자만 요약에 남는다.
export function stageThresholds(steps) {
  const t = {};
  for (const s of steps) {
    t[`http_req_duration{stage:${s}}`] = ['max>=0'];
    t[`http_req_failed{stage:${s}}`] = ['rate>=0'];
    t[`http_reqs{stage:${s}}`] = ['count>=0'];
  }
  return t;
}

// 끝나면 요약을 loadtest/results/ 아래 JSON으로 남긴다(날짜+스크립트 이름).
// 폴더는 미리 만들어 둬야 한다(README 참고). 결과에 주소가 들어갈 수 있어 git에서 제외돼 있다.
// stageInfo: { steps: [5,10,...], holdSec: 60 } 를 주면 단계별 한 줄씩 더 적는다(유지 구간만 센 숫자).
export function makeSummary(scriptName, data, stageInfo) {
  const stamp = new Date().toISOString().replace(/[:.]/g, '-');
  const m = data.metrics;
  const val = (name, key) => (m[name] && m[name].values[key] !== undefined ? m[name].values[key] : NaN);
  let text =
    `\n[${scriptName}] (준비 요청 제외) 요청 ${val(`http_reqs${MAIN}`, 'count')}건, ` +
    `p95 ${val(`http_req_duration${MAIN}`, 'p(95)').toFixed(0)}ms, 실패율 ${(val(`http_req_failed${MAIN}`, 'rate') * 100).toFixed(2)}%\n`;
  if (stageInfo) {
    text += '단계별(유지 구간만):\n';
    for (const s of stageInfo.steps) {
      const count = val(`http_reqs{stage:${s}}`, 'count');
      text +=
        `  ${s}명: 요청 ${count}건, 초당 ${(count / stageInfo.holdSec).toFixed(1)}건, ` +
        `p95 ${val(`http_req_duration{stage:${s}}`, 'p(95)').toFixed(0)}ms, ` +
        `실패율 ${(val(`http_req_failed{stage:${s}}`, 'rate') * 100).toFixed(2)}%\n`;
    }
  }
  text += `결과 파일: loadtest/results/${stamp}_${scriptName}.json\n`;
  return {
    stdout: text,
    [`loadtest/results/${stamp}_${scriptName}.json`]: JSON.stringify(data, null, 2),
  };
}
