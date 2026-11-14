# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.
자세한 결정·기록은 docs/backlog.md(각 S번호 항목 + "끝난 일" 표), 배포는 docs/deploy.md, API는 docs/api.md.

마지막 갱신: 2026-11-15 집 PC (**S17 AI 챗봇 a~d 끝, 로컬만 — 배포는 S17 끝(h)에 한 번**)

## 지금 어디까지
- **S0~S14 + UI1~UI3 + S16 완료·배포됨.** DB는 **V13까지**(S17은 표 변경 없음). 운영 화면은 2026-11-14 Deploy #18(커밋 dbef29b 이미지)부터 **Flutter 웹**.
- **S17(AI 챗봇, 진행 중)**: 결정·단계는 backlog S17 항목. **2026-11-14 방향 변경: 욕설 검사 대신 챗봇(RAG·도구 호출) 먼저, 개발은 전부 로컬 Ollama.** 운영(t3.micro 1GB)은 로컬 LLM 불가 → 배포(h) 때 "끄기 vs API로 켜기" 결정.
  - a 모델 실측(qwen3:8b, 시간 제한 60초, `docs/llm-model-test.md`·`docs/llm-bench.mjs`) → b LLM 토대(`LLM_MODE` off 기본/ollama, Spring AI 2.0.2 `spring-ai-ollama` 모듈, 재시도 0, 연결 5초·호출 전체 60초) → c `POST /chat`(api.md 43번, 단어 OR로 판매중·예약중 중고 글 최신 5건 → AI → answer + sources) → d Flutter `/chat` 화면(답은 Text만, 90초) **완료**.
  - 남은 것: **e 도구 호출(research 먼저)** → f 횟수 제한 429·동시 호출 상한 → (g 뜻 검색, e 뒤 결정) → h 배포.
- **S16(React → Flutter 웹)**: 단계 a~p 전부 끝. 결정·단계별 기록은 backlog S16 항목과 "끝난 일" 표.
  - 코드: `flutter_app/`(lib = 화면·서버 통로, test = 위젯 테스트 1274개, e2e = Playwright 10개 + 옮김/안 옮김 표·손 확인표가 `flutter_app/e2e/README.md`).
  - CI: `backend` + `flutter`(analyze → test → build web --web-content-hash → e2e → build/web 아티팩트) → `images`(demo-web = Flutter). React 작업·`frontend/` 폴더는 S16-p(40af723)에서 삭제.
  - 운영 확인(사용자): 목록·상세·새로고침 딥링크·로그인·사진 올리기·화면 이동 OK. 첫 배포 때 "화면 이동 시 0.3초 겹침" → NoTransitionPage로 고쳐 재배포.
  - 첫 로딩: 전송 4.0MB, 빠른 조건 약 0.9초 / 느린 모바일 조건 약 23초(React는 0.76MB·4초) — 알려진 한계로 받아들임. Ctrl+F·글자 선택 제한도 알려진 한계.
  - 되돌리기: 화면만 React로 = `seokam36/demo-web:80431c308c0ad7d6d519a597ed21f3e63840ce6b`(docs/deploy.md "web만 React로 되돌리기"). 옛 React 코드는 `git show 80431c3:frontend/...`.
- **방향**: 학습·포트폴리오 중심. 다음 큰 기능 순서는 **S17 AI → S15 글 번역**(사용자 결정 2026-11-12).

## 다음에 할 일
- **바로 다음 행동: S17-e(도구 호출) research → planner.** research할 것: Spring AI 2.0.2 도구 호출(@Tool 등)을 `LlmClient` 인터페이스 뒤에 숨기는 법, 가짜 LLM으로 "도구를 불렀다" 테스트, qwen3:8b tools 안정성, 안 되면 "AI가 검색 조건을 JSON으로 뽑고 서버가 검색" 대안, 글에 숨긴 지시문이 도구 인자를 조종할 위험. planner 때 같이: 최신순 대신 잘 맞는 순 정렬(맞은 단어 수 등)과 출처 5개/10개를 실제 qwen3로 비교(사용자가 "5개는 적지 않나" 물음), 프롬프트에 "해당하는 글을 모두" 검토, 출처 중복 제거.
- (선택, 언제든) S17 손 확인: `.env`에 `LLM_MODE=ollama` → 백엔드 + `flutter run -d chrome` → 로그인 → 상단바 "챗봇". 확인표는 `flutter_app/e2e/README.md`(한글 조합 Enter, 첫 호출 30초+ 대기, 휴대폰 키보드 등).
- 에이전트 설정 검토만 남음: deploy 허용 경로에 `flutter_app/Dockerfile`·`nginx.conf`·`.dockerignore` 추가 — S17-h 배포 전에 사용자에게 묻기.
- Notion: S17-a·b·c 단계 기록, 도구 사전 Ollama·Spring AI 페이지 완료(S17-d는 화면이라 안 씀). 남은 것: 도구 사전에 Flutter·go_router·dio·provider 개념 페이지 필요 여부 묻기, 카드 요약 "배포됨", 아키텍처 그림에 S3, IAM 역할·IMDS 페이지 필요 여부. (진행 현황 S13·S14 체크는 2026-11-14 함)
- **EC2는 2026-11-14 배포 뒤 켜져 있을 수 있다.** 안 쓰면 콘솔에서 중지.
- 운영 확인 남은 것(선택): 학교 인증된 두 번째 계정이 생기면 deploy.md "S13·S14 배포 후 확인" 4·5번(쪽지·대화 신고). `flutter_app/e2e/README.md` 손 확인표(아이폰 사진 선택, 한글 입력 조합 검색, 스크린리더 등).
- (나중, 포트폴리오 트러블슈팅 후보) g6-1(옛 코드로 테스트 6개 실패 재현), S13-h CI 빨간불(headless shell → chromium 채널), S16 CI e2e "두 번째 칸 글자 빠짐"(로컬 재현 실패, 누른 칸 기준 입력 + 재시도로 해결), S16 화면 겹침(MaterialPage 300ms + 투명 배경, 고치기 전 실패 테스트 있음).

## 진행 중인 단계
- **S17**: a~d 커밋·push됨(b1877c6, 0d788b5, 89e1aa1, 6b3501e). e는 아직 시작 안 함(research부터). 진행 중인 에이전트 없음.
- 커밋 안 된 변경: `.claude/launch.json`(일부러 커밋 안 함, 집 PC 전용 미리보기 설정. React 항목은 이제 의미 없음).
- 미룬 것: backlog 각 S번호 항목의 "나중(…리뷰)" 줄. 큰 것만:
  - S17: **h 배포 때** `flutter_app/nginx.conf` `/api/` 응답 대기 기본 60초 = AI 60초라 nginx가 먼저 504(→ `/api/chat`만 넉넉히). **f에서** ollama-call 스레드 풀 상한 없음·요청이 Tomcat 스레드 60초 점유·컨트롤러가 로그인 회원을 안 받음. 검색이 `LIKE '%…%'` OR라 전체 스캔(커지면 pg_trgm 또는 g). 실제 qwen3가 "자전거 있어?"에 판매 글을 빠뜨리고 나눔만 답함.
  - S16: 미리보기 cacheWidth만(가로로 긴 사진 살짝 확대), file_picker 하나의 input 재사용, Safari·VoiceOver 파일 선택 실측, e2e mockApi에 타입 검사 없음(api.md 바뀌면 조용히 낡음), Flutter 엔진이 바뀌면 e2e `typeInto`(입력 요소=activeElement 가정)가 먼저 깨짐.
  - S14: 관리자 대화 열람 감사 기록(관리자 늘리기 전 필수), 쪽지 횟수 제한·페이징, 200통 넘는 방의 이전 쪽지.
  - S13: 가격 "5000" 문자열·5000.9 받아짐 확인, 아이폰 HEIC·48MP.
  - S12·S11: 배열·숫자 날짜가 LocalDate로 받아짐(Jackson 설정 한 곳에서 막기), 수정 중 삭제 500.
  - S10: 메일 해시 소금 없음(HMAC), Gmail 하루 500통 한도.
  - S9 이전: 목록 페이징, 관리자 목록 페이징, `.gitattributes` 줄바꿈 규칙.
- 운영 서버 메모: EC2 `~/board/.env`에 `PHOTO_S3_BUCKET`(값은 저장소에 안 씀), compose는 S13-g6 버전(옛 파일 `docker-compose.yml.bak-20261113`). IMDS 홉 제한 2·IMDSv2 필수·역할 `board-ec2-role`. S16은 compose 변경 없음(이미지 이름 그대로).
- 선택 정리: EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **노트북에서 Flutter 작업하려면 준비 2가지**:
  1. Flutter 3.50.0 설치: `git clone --depth 1 -b stable https://github.com/flutter/flutter.git %USERPROFILE%\flutter` → PATH에 `...\flutter\bin` → `flutter doctor`(웹만이라 Chrome ✅면 됨, Android·VS ❌ 무시). 첫 실행은 20분 넘게 걸릴 수 있음.
  2. 로컬 `.env`에 `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5175`(.env.example 주석 참고). Flutter 개발 서버 프록시(web_dev_config.yaml, 포트 5175)가 Host를 바꿔서, 이게 없으면 로그인 등 POST가 403.
- **Flutter 웹 작업 메모**:
  - 이동은 `context.go`만(router.dart 주석). 모든 경로는 `_page()` = NoTransitionPage(전환 시간 0). `builder:`로 되돌리면 화면이 0.3초 겹친다(no_transition_test가 잡음).
  - 실제 브라우저 자동 확인: Flutter 입력칸은 `fill()` 대신 "칸 클릭 → 그 칸 포커스 확인 → `keyboard.type` → `toHaveValue`"(e2e `typeInto`). 공감 버튼은 role=switch, 에러 안내는 role=alert인데 글자가 aria-label(`toHaveAccessibleName`). 상단바·목록 글은 link가 아니라 text로 잡힘.
  - e2e는 빌드된 build/web을 시험한다: 항상 `flutter build web --web-content-hash` 다음 `flutter_app/e2e`에서 `npm test`. 로컬은 Edge(msedge), CI는 chromium 채널. 로컬에서 통과해도 CI(리눅스 headless)에서만 깨질 수 있다.
  - 첫 로딩 측정은 nginx 이미지 + 백엔드 연결이 있어야 한다(앱이 시작 때 /csrf·/auth/me를 기다림). 단독 nginx 컨테이너면 /api가 DNS 대기로 멈춰 "불러오는 중"만 보인다.
  - Flutter build 경고 "canvaskit은 해시 없음" → nginx가 /canvaskit/를 no-cache로 둠(의도).
- **로컬 LLM(S17)**: 집 PC에 Ollama 0.40.16 + 모델 qwen3:8b·exaone3.5:7.8b·gemma3:12b(`C:\Users\seoka\.ollama\models`, 약 18GB). 서버 `http://localhost:11434`, 작업 표시줄 라마 아이콘. 설치 전에 열린 셸은 `ollama` 명령을 모름 → 새 창 또는 `%LOCALAPPDATA%\Programs\Ollama\ollama.exe`. 실제 Ollama 테스트는 `OLLAMA_LIVE_TEST=true`일 때만(`OllamaLiveTests`, `ChatLiveTests`). 기본 `LLM_MODE` 없음 = off라 CI·노트북은 그대로 돈다. **노트북에서 ollama 모드로 하려면** Ollama 설치 + `ollama pull qwen3:8b`, 사양 미확인(그래픽카드 없으면 CPU로 약 6배 느림).
- **Ollama가 GPU를 안 쓰고 조용히 CPU로 돌면**(`ollama ps`가 `100% CPU`): `%LOCALAPPDATA%\Ollama\server.log`에 `--list-devices failed: 0xc0000005`. 원인은 `C:\Windows\System32\msvcp140.dll`이 옛 버전(집 PC는 14.24였음). Microsoft VC++ 재배포 패키지 x64 최신 설치(14.44) → Ollama 재시작으로 해결(2026-11-14). 첫 GPU 실행만 30초+ 느림.
- **Spring AI 함정**: 기본 재시도 10회(대기 최대 3분씩)라 끄지 않으면 60초 제한이 수십 분이 됨. JDK HttpClient readTimeout은 응답 헤더까지만 재서, 본문이 천천히 오면 안 끊김 → `OllamaLlmClient`는 별도 스레드 + `future.get(timeout)`. 스타터 대신 `spring-ai-ollama` 모듈만(타임아웃을 Ollama에만 걸려고).
- **집 PC 스마트 앱 컨트롤은 2026-11-13에 끔.** 켜져 있으면 dartvm.exe·oxlint·Playwright 브라우저를 오류 없이 막는다(이벤트 로그 `Microsoft-Windows-CodeIntegrity/Operational` Id 3077/3033). 노트북에서 비슷하면 같은 로그부터.
- **이 PC엔 `gh` CLI가 없다.** CI 상태는 `curl https://api.github.com/repos/seokam36/board-vibe-coding/actions/runs`(공개 저장소). 비로그인 API는 **시간당 60번** — 30초마다 조회하면 금방 막힌다(2분 간격으로). CI 로그 본문은 API로 못 받음 → 사용자 Chrome으로 Actions 잡 페이지 읽기.
- **Deploy 전에 그 커밋 CI(images 포함)가 초록인지 확인 필수.** 빨강이면 Docker Hub latest는 옛 버전이다. main에서는 기다리는 실행이 최신 1개만 남아 중간 커밋 images가 건너뛰어질 수 있다(되돌리기용 커밋은 따로 push해서 images를 받아 둔 이유).
- **Git Bash `sed -i`로 docs 파일을 고치지 말 것**(줄바꿈 때문에 수십 줄이 바뀐 적 있음). Edit 도구로.
- **사용자에게 서버 명령을 줄 때 `<서버IP>`·`<버킷 이름>` 같은 자리표시를 쓰지 말 것**(그대로 복사돼 .env에 들어간 적 있음). 실제 값 넣은 완성 명령 또는 "이 부분만 바꿔"를 한 줄로. 사용자 터미널은 PowerShell, 키 경로 `"$HOME\.ssh\board-ec2"`. 서버/내 PC 어디서 칠지 매번 표시. 집 PC EC2 키는 암호가 있어 ssh·scp는 사용자가 직접.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `V14__add_...sql`, UNIQUE는 `uk_` 관례). 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate.
- **FK·UNIQUE·CHECK 제약에는 반드시 이름**(서비스가 제약 이름으로 404/401/409 구분, `ConstraintViolations`). V1의 `posts.author_id` FK만 해시 이름이라 글쓰기는 "23503이면 401".
- **잠금 순서(S14)**: 탈퇴는 회원 행 FOR UPDATE 먼저. 쪽지 쓰기·답장·읽음·대화 신고는 회원 행 FOR KEY SHARE(`lockForKeyShare`, id 순) 먼저, 그다음 방·쪽지. 어기면 40P01 교착 → 500.
- **DB 오류 메시지 언어는 영어(기본값) 유지.** `lc_messages`를 바꾸면 경쟁 상황에서 500.
- 경쟁 조건 테스트는 `LockRaceSupport`, 유령 토큰은 `TestLogin.행없는_회원_토큰(jwtEncoder)`, 인증 회원은 `TestLogin.인증된_사용자(...)`·`학교_인증_처리(jdbc, username, 학교명)`. 글쓰기·댓글·공감·신고 테스트는 인증 회원 + 같은 학교 글.
- 테스트 메일은 `@Primary` 가짜 `VerificationMailSender`(SchoolEmailCodeApiTests의 RecordingSender). 인증 Redis 키 `email-verify*`, 막혔을 때 푸는 법은 deploy.md "학교 인증 코드가 막혔다는 문의가 오면".
- 로컬 실제 메일: `.env`에 MAIL_* + `MAIL_MODE=smtp`. 개인 Gmail로 받으려면 **로컬 DB에만** gmail.com을 school_email_domains에 넣었다가 끝나면 삭제. 화면 확인은 `MAIL_MODE=log` + 서버 로그 `[가짜 메일 발송] ... code=`.
- 옛 이미지로 되돌리기(app): V3~V7은 되돌려도 뜬다(deploy.md (b)). V7 후 S10 이미지면 후기 쓴 회원 탈퇴가 500.
- 관리자는 운영 DB SQL로만 지정(deploy.md "관리자 지정·해제"), 지정 후 재로그인.
- **Jackson 3 설정 이름 함정**: 숫자 enum 막기는 `spring.jackson.datatype.enum.fail-on-numbers-for-enums`(옛 이름이면 앱이 안 뜸).
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173·Redis 6380을 쓰고 있을 수 있다.
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일 + `--data-binary @파일`. psql 한글 SQL도 파일로.
- PostgreSQL 정렬이 한글 가나다순과 달라 학교 이름 순서는 서버 코드에서 정렬.
- **운영 테스트 계정 = `wntjrdka`(연세대 인증).** 비밀번호는 사용자만 앎. 테스트 글·후기는 확인 뒤 삭제. 다른 계정 인증: [서버] `UPDATE users SET verified_school_id = (SELECT id FROM schools WHERE name = '연세대학교'), verified_at = now() WHERE username = '...';` 후 재로그인.
- 로컬 DB에는 S16 확인용 테스트 계정·글·중고 글이 남아 있다(로컬 전용, 지워도 됨).
