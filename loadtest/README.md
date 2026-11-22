# 부하 테스트 (S27)

## 무엇을, 왜 하나
운영 서버(EC2 t3.micro 한 대)가 "사람이 몰리면 어디서 먼저 힘들어하는지"를 숫자로 본다.
식당에 손님을 5명, 10명, 20명, 40명씩 늘려 보내며 어느 순간부터 음식이 늦게 나오는지 재는 것과 같다.
k6라는 도구가 가짜 손님(가상 사용자)이 되어 **읽기(GET) 요청만** 보낸다.
병목을 찾아 고친 뒤 **같은 스크립트로 다시 재서** 전후를 비교한다(기록은 `docs/loadtest.md`).

## 파일
| 파일 | 역할 |
|------|------|
| `read-smoke.js` | 스모크. 1명, 30초. 스크립트·주소가 맞는지만 확인 |
| `read-ramp.js` | 5 → 10 → 20 → 40명, 단계당 1분(총 약 5분 15초). 단계 값은 파일 맨 위 상수 |
| `lib/common.js` | 두 스크립트가 같이 쓰는 부분(요청 목록, BASE_URL 검사, 요약 저장) |
| `results/` | 결과 JSON(git 제외, 아래 참고) |

부르는 주소(모두 로그인 없이 읽는 GET): `/posts`(주력), `/posts/{id}`, `/posts/{id}/comments`, `/market-items`, `/visa-reviews/stats`.
`WEB_URL`을 주면 화면 첫 파일 `/`도 부른다(운영 nginx에서만 의미 있음).

## 지킬 것
- **GET만** 보낸다. 쓰기(POST 등), 챗봇, 허가서, 사진 주소는 스크립트에 없다(비용·데이터 오염 방지).
- **집 PC에서만** 돌린다.
- **운영 실행은 매번 사용자 승인**을 받는다.
- 서버를 켠 뒤 **10분 예열** 후 시작한다(켠 직후는 느려서 결과가 흔들린다).
- 측정 중 SSH 창에서 `docker stats`, `free -m`을 기록한다(컨테이너별 메모리·CPU).
- 공개 GET에는 호출 횟수 제한이 없다. 멈추는 장치는 스크립트(아래 자동 멈춤)와 Ctrl+C뿐이다.

## k6 설치 (한 번만)
```powershell
winget install k6 --source winget
k6 version   # 설치 확인
```

## 실행 (저장소 루트에서)
`BASE_URL`에는 **접두사까지** 넣는다. 끝에 `/`는 붙이지 않는다. `BASE_URL`이 없으면 요청을 하나도 보내지 않고 에러로 끝난다.

```powershell
# 결과 폴더를 먼저 만든다(없으면 요약 저장이 안 된다)
New-Item -ItemType Directory -Force loadtest/results

# 로컬 (bootRun은 /api 접두사가 없다)
$env:BASE_URL = "http://localhost:8080"
k6 run loadtest/read-smoke.js

# 운영 (사용자 승인 후에만. 운영은 nginx 뒤라 /api가 붙는다)
$env:BASE_URL = "https://<사이트주소>/api"
$env:WEB_URL  = "https://<사이트주소>"   # 선택: 화면 첫 파일도 잴 때만
k6 run loadtest/read-smoke.js            # 먼저 스모크
k6 run loadtest/read-ramp.js             # 이상 없으면 단계 증가
```

## 자동 멈춤 기준 (thresholds + abortOnFail)
| 스크립트 | 기준 | 지켜보는 시간 |
|----------|------|---------------|
| 스모크 | 실패율 1% 미만, p95 1초 미만 | 5초 |
| 단계 증가 | 실패율 10% 미만, p95 5초 미만 | 20초 |

기준을 넘은 상태가 이어지면 k6가 스스로 중단한다(서버를 더 괴롭히지 않으려고). 이상하면 언제든 `Ctrl+C`.
시작할 때 글 id를 뽑는 준비 요청 1번은 기준과 결과 숫자에서 뺀다(`phase:setup` 꼬리표).

## 단계별 숫자 읽기
단계 증가가 끝나면 화면에 이렇게 단계마다 한 줄씩 나온다(유지 1분 구간만 센 숫자, 전환 구간은 뺌).
```
단계별(유지 구간만):
  5명: 요청 290건, 초당 4.8건, p95 35ms, 실패율 0.00%
  10명: ...
```
이 줄을 그대로 `docs/loadtest.md` 표에 옮긴다. JSON 파일에서는 `http_req_duration{stage:20}` 같은 이름으로 찾을 수 있다.

## 결과 파일
- 위치: `loadtest/results/<날짜시간>_<스크립트이름>.json` (실행이 끝나면 자동 저장)
- 결과에 사이트 주소가 들어갈 수 있어서 `.gitignore`로 커밋에서 제외했다. 올리지 말 것.
- API별 숫자는 JSON 안 `name` 태그(`GET /posts` 등)로 구분된다.
- 숫자는 `docs/loadtest.md` 표에 옮겨 적는다.

## 측정 당일 체크리스트
- [ ] EC2 켜기(켠 시각 적기) → 10분 예열
- [ ] 현재 운영 이미지 SHA 적기 (`docs/loadtest.md` 측정 조건)
- [ ] CloudWatch **1분 상세 모니터링 켜기**
- [ ] CPUCreditBalance(크레딧 잔고) 시작값 적기
- [ ] 운영 글 수 적기
- [ ] 사용자 승인 받기 → SSH 창에서 `docker stats`, `free -m` 반복 기록 시작
- [ ] 스모크 → 단계 증가 순서로 실행
- [ ] 끝나면 CPUCreditBalance 끝값, CPUUtilization 최대, board-app 재시작 횟수(`docker ps`/`docker inspect`) 적기
- [ ] CloudWatch **1분 상세 모니터링 끄기**(켜 두면 요금이 나온다)
- [ ] EC2 끄기(쓰지 않을 때)
