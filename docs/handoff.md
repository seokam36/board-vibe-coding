# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.
자세한 결정·기록은 docs/backlog.md(각 S번호 항목 + "끝난 일" 표), 배포는 docs/deploy.md, API는 docs/api.md.

마지막 갱신: 2026-11-16 (**S20 유학생 인증 진행 중: S20-a·b·c 커밋, 다음은 S20-d 판정 규칙**)

## 지금 어디까지
- **S0~S14 + UI1~UI3 + S16 + S17 + S18 완료·배포됨.** 운영은 2026-11-15 S18 배포(커밋 2f4e560 이미지), 운영 챗봇 gemini(무료 등급) 켜짐. 그 뒤 커밋(S18-g 정리, S20-a~c)은 **아직 운영에 안 올라감** → S20 배포(S20-i) 때 같이.
- **방향 변경(2026-11-16 사용자)**: "외국인 유학생 통합 에타". **유학생만 글을 쓴다** — 표준입학허가서 인증이 입장권(학교 메일은 한국 학생도 있어 유학생 여부를 모름). S19(챗봇 일반 답변·사이트 안내)는 **기능을 다 만든 뒤로**, S15 번역·비자 후기 검색·욕설 검사·뜻 검색도 나중으로.
- **S20(유학생 인증)** — 결정 전부 backlog S20 항목("최종 방향" 줄 + 아래 메모):
  - 서류: 재학증명서는 국적 칸이 없어(사용자가 실물 확인) **표준입학허가서**(외국인 전용, 법무부 공통 양식, 국적·학교명·번호 있음, 여권번호도 있어 가리게 안내).
  - 확인: **반자동** — Google Cloud Vision(REST, 학습 미사용, 월 1,000장 무료, 결제 계정 필요)이 글자를 읽고 우리 판정 규칙이 판단. **가안**: 4가지(허가서 제목·외국 국적·학교 별칭 일치·번호 읽힘+미사용) 다 맞으면 자동 승인, 그 밖은 관리자 대기, **자동 반려 없음**. Gemini 무료는 공식 약관상 개인정보 금지라 못 씀.
  - 승인 = 기존 `users.verified_school_id` 칸 채우기(글쓰기 등 기존 검사 그대로). 기존 메일 인증 회원(운영 테스트 계정 2개)은 **초기화 안 함**(APPROVED로 봄).
  - 사진은 대기 중에만 보관(S3 `permits/`), 결과 나오면 즉시 삭제, 14일 넘으면 자동 만료. 번호는 원문 대신 **HMAC 해시만**(`PERMIT_HASH_SECRET`).
  - 끝난 단계: **S20-0**(조사) · **S20-a** 신청 API·V14(ba693fa) · **S20-b** 관리자 심사 API·HMAC(d6540b6) · **S20-c** 14일 자동 만료 작업(db455d8). 테스트 1753개 통과.
- DB는 **V14까지**(permit_requests, school_name_aliases — 5개 학교 영문 별칭).

## 다음에 할 일
- **S20-d 판정 규칙 시작**: 만들기 전에 무엇을 만드는지 사용자에게 설명하고 OK 받기. 가짜 판독기(글자 입력)로 4가지 판정 + 가안 자동 승인, Vision 실패·애매하면 관리자 대기. 학교 별칭 표 사용, 견본은 2013 숭실대 허가서 한 장뿐(실물 규칙 보정은 S20-h).
- 그다음: S20-e Cloud Vision 클라이언트(가짜 구글 서버 테스트) → S20-f 메일 인증 걷어내기(API·화면·코드, DB 칸은 둠) → S20-g Flutter 화면(신청·상태·철회 확인 창·관리자 목록) → **사용자 카드 등록 뒤** S20-h 실물 시험 → S20-i 배포.
- **사용자 할 일(카드 등록)**: Google Cloud에 **새 프로젝트**(예: board-vision)를 만들어 거기에만 결제 계정 연결 → Vision API 켜기 → Vision 전용 키(API 제한) → 예산 알림. **Gemini 키가 있는 SpringStudy 프로젝트에는 결제를 붙이지 말 것**(챗봇까지 유료가 될 수 있음).
- **S20-i 배포 때 할 것**: 서버 `.env`에 `PERMIT_HASH_SECRET`(32바이트 이상, 한 번 정하면 바꾸지 않음) + Vision 키, compose에 넘기는 줄(deploy 담당), **S3 버킷 `permits/` 접두사에 14일 수명 주기 규칙**(콘솔, "모든 객체" 고르면 중고 사진까지 지워짐 주의, 버전 관리 켜져 있으면 이전 버전 삭제도), S3 기본 암호화 켜져 있는지 확인.
- Notion: S20 카드 + S20-a·b·c 페이지, 진행 현황 S20 줄 완료. 메인 페이지 아키텍처·배포 흐름 S18 기준 갱신, 하위 페이지 "아키텍처 쉽게 보기(EC2·S3·RDS)", "로컬 LLM vs 클라우드 LLM" 추가. S20 끝나면 🧭 파트 전체 정리.
- (선택, 예전부터) 구글 503 빈도 세기, 상단바 닉네임 위치, 운영 테스트 중고 글 15개는 실제 회원 받기 전에 지우기, EC2는 꺼 두기.

## 진행 중인 단계
- **S20 진행 중**: S20-c까지 build·tester·review·커밋 끝. S20-d는 아직 시작 전(설명·승인 전). 진행 중인 에이전트 없음.
- 커밋 안 된 변경: `.claude/launch.json`(일부러 커밋 안 함), `frontend/`(예전 React의 빌드 결과·node_modules 찌꺼기, 저장소엔 없는 폴더 — 커밋하지 말 것).
- 미룬 것: backlog 각 S번호 항목의 "나중(…리뷰)" 줄. S20은 "나중(S20-a 리뷰)", "나중(S20-b 리뷰)", "나중(S20-c 리뷰)" 메모 — S20-d·g에서 챙길 것(승인 대 메일 인증 경쟁, 철회 시 번호 해시 사라짐, 화면 철회 확인 창 등).
- 운영 서버 메모: EC2 `~/board/.env`에 `PHOTO_S3_BUCKET`·`LLM_MODE=gemini`·`GEMINI_API_KEY`(값은 저장소에 안 씀, 권한 600), compose는 S18-e 버전. IMDS 홉 제한 2·IMDSv2 필수·역할 `board-ec2-role`. 직전 배포로 되돌리기 = Deploy #19 `8d8c14d` 이미지, **되돌리기 전에 `.env`의 `LLM_MODE`를 off로 먼저**(deploy.md "S18 되돌리기" 0번). S20 배포 뒤 되돌리기는 V14가 생기므로 deploy.md에 새로 적어야 함.
- 선택 정리: EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **로컬에서 앱(bootRun)을 켜려면 `PERMIT_HASH_SECRET`이 필요하다(S20-b).** 비거나 32바이트 미만이면 시작 실패. `JWT_SECRET`을 넣는 곳에 같은 방식으로 추가, 만드는 법은 `.env.example`. 테스트는 설정 파일의 랜덤 값을 써서 필요 없음.
- **허가서(S20) 개인정보 규칙**: 로그에 사진 key·`permits/`·허가서 번호·해시를 남기지 않는다(`PhotoDeleteQueue`가 permits/ key를 "허가서 사진 N건"으로 가림). `spring.datasource.hikari.data-source-properties.logServerErrorDetail=false`로 PostgreSQL 오류의 Detail(키 값)이 로그에 안 나감 — 제약 이름은 남아서 409 구분은 그대로.
- **주기 작업은 cron 대신 fixedDelay + initialDelay**(EC2를 평소 꺼 두므로 매일 정해진 시각 cron은 거의 안 돈다). 사진 청소부(10분)·허가서 만료(기동 3분 뒤 + 1시간)가 이 방식. 테스트는 `app.scheduling.enabled=false`라 스케줄이 안 돌고 `runOnce`를 직접 부른다.
- **IDE(VS Code Java)의 "getId() undefined"·"log cannot be resolved" 진단은 무시**: Lombok을 IDE가 못 읽는 것뿐, `./gradlew build`·test는 통과.
- **노트북에서 Flutter 작업하려면 준비 2가지**:
  1. Flutter 3.50.0 설치: `git clone --depth 1 -b stable https://github.com/flutter/flutter.git %USERPROFILE%\flutter` → PATH에 `...\flutter\bin` → `flutter doctor`(웹만이라 Chrome ✅면 됨). 첫 실행은 20분 넘게 걸릴 수 있음.
  2. 로컬 `.env`에 `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5175`. 없으면 Flutter 개발 서버에서 로그인 등 POST가 403.
- **Flutter 웹 작업 메모**:
  - 이동은 `context.go`만(router.dart 주석). 모든 경로는 `_page()` = NoTransitionPage. `builder:`로 되돌리면 화면이 0.3초 겹친다.
  - 실제 브라우저 자동 확인: Flutter 입력칸은 `fill()` 대신 "칸 클릭 → 포커스 확인 → `keyboard.type` → `toHaveValue`"(e2e `typeInto`, 챗봇 칸은 라벨 /질문/으로 찾음). 공감 버튼은 role=switch, 에러 안내는 role=alert인데 글자가 aria-label.
  - e2e는 빌드된 build/web을 시험한다: `flutter build web --web-content-hash` 다음 `flutter_app/e2e`에서 `npm test`. 로컬은 Edge, CI는 chromium.
- **제미나이(S18)**:
  - 로컬에서 쓰려면 `.env`에 `GEMINI_API_KEY=`(키 값) + `LLM_MODE=gemini`. **로컬에서 부르면 운영과 같은 무료 한도를 씀**(같은 키). 실제 호출 테스트는 `GEMINI_LIVE_TEST=true`일 때만(`GeminiLiveTests`), 실측 스크립트 `docs/llm-bench-s18.mjs`(결과 `docs/bench-s18-run.json`은 .gitignore).
  - 키는 새 형식 `AQ.`로 시작(예전 `AIza`). 키 누출 검사 패턴은 `x-goog-api-key\|AIza\|AQ\.`. 커밋 전에 `git diff --cached`를 grep해서 0인지 확인해 왔음.
  - 무료 등급은 구글이 바쁘면 가끔 503 — 다시 물으면 대개 됨. 키 오류면 401, 없는 모델 404, 한도 429.
  - Boot 4에서 오류 본문 설정 이름은 `spring.web.error.include-message`(옛 `server.error.*`는 안 먹음).
- **compose가 바뀌는 배포는 Deploy 버튼만으로 안 됨**: Deploy는 이미지만 pull·up. compose를 먼저 백업·scp하고, 새 비밀 값은 서버 `.env`에 nano로 직접(키를 셸 프롬프트에 붙이지 않기, `cat .env` 금지). S10 메일·S13 사진·S18 제미나이·S20 허가서 모두 같은 패턴.
- **로컬 LLM(S17)**: 집 PC에 Ollama 0.40.16 + qwen3:8b 등. 실제 Ollama 테스트는 `OLLAMA_LIVE_TEST=true`일 때만. **백엔드 테스트는 Docker Desktop이 켜져 있어야 함**(Testcontainers) — `C:\Program Files\Docker\Docker\Docker Desktop.exe`.
- **Ollama가 GPU를 안 쓰고 CPU로 돌면** `%LOCALAPPDATA%\Ollama\server.log`에 `--list-devices failed: 0xc0000005` → VC++ 재배포 패키지 x64 최신 설치(2026-11-14).
- **Spring AI 함정(ollama 모드)**: 기본 재시도 10회라 끄지 않으면 60초 제한이 수십 분이 됨. 읽기 제한은 전체 상한 + 5초, "시간 초과" 판정은 Future 하나만(Gemini 클라이언트도 같은 방식).
- **집 PC 스마트 앱 컨트롤은 2026-11-13에 끔.** 켜져 있으면 dartvm.exe·oxlint·Playwright 브라우저를 오류 없이 막는다.
- **이 PC엔 `gh` CLI가 없다.** CI 상태는 `curl https://api.github.com/repos/seokam36/board-vibe-coding/actions/runs?head_sha=<40자리 SHA>`(공개 저장소, 비로그인 **시간당 60번** — 2분 간격). 잡별 결과는 `.../actions/runs/<run id>/jobs`.
- **Deploy 전에 그 커밋 CI(images 포함)가 초록인지 확인 필수.**
- **docs 파일을 Git Bash `sed -i`로 고치지 말 것**(줄바꿈 때문에 수십 줄이 바뀐 적 있음). Edit 도구로. 서브에이전트도 파이썬·sed 일괄 치환 금지라고 알려 줌. `cat >> 파일 <<EOF`로 줄을 붙일 땐 **파일 끝에 줄바꿈이 있는지** 먼저 확인(backlog는 CRLF라 `printf '%s\r\n'`로 붙여 옴).
- **사용자에게 서버 명령을 줄 때 `<서버IP>` 같은 자리표시를 쓰지 말 것.** EC2를 켤 때마다 IP가 바뀌니 사용자에게 IP를 받아 완성 명령을 준다. 사용자 터미널은 PowerShell, 키 경로 `"$HOME\.ssh\board-ec2"`, ssh·scp는 사용자가 직접(키 암호). 서버/내 PC 어디서 칠지 매번 표시.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `V15__...sql`, UNIQUE는 `uk_` 관례, 상태별 중복 금지는 부분 UNIQUE 인덱스). 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate. 테스트·더미 데이터는 V 파일에 넣지 않는다(`docs/sql/`처럼 손으로 실행하는 파일).
- **FK·UNIQUE·CHECK 제약에는 반드시 이름**(서비스가 제약 이름으로 404/401/409 구분). V1의 `posts.author_id` FK만 해시 이름.
- **잠금 순서(S14)**: 탈퇴는 회원 행 FOR UPDATE 먼저. 쪽지 쓰기·답장·읽음·대화 신고는 회원 행 FOR KEY SHARE 먼저, 그다음 방·쪽지. 어기면 40P01 교착 → 500. **허가서(S20)**: 승인·반려·만료·철회·탈퇴 모두 신청자 회원 행 FOR UPDATE 먼저 → 그다음 신청 행(FOR UPDATE 또는 삭제) → 상태 재확인(`AdminPermitService.lockPending`). 신청(POST)은 회원 행 FOR KEY SHARE.
- **사진 대기 표(photo_delete_queue) 함정**: 청소부는 표의 key 중 `market_item_photos`가 안 쓰는 것을 지운다. 허가서 사진은 "저장 전에 적고 INSERT 트랜잭션에서 떼기"(신청), "결정·삭제 트랜잭션 안에서 적고 커밋 뒤 지우기"(승인·반려·만료·철회·탈퇴)만 쓴다. 대기 중인 사진 key를 표에 그냥 남겨 두면 10분 뒤 지워진다.
- **DB 오류 메시지 언어는 영어(기본값) 유지.**
- 경쟁 조건 테스트는 `LockRaceSupport`, 유령 토큰은 `TestLogin.행없는_회원_토큰(jwtEncoder)`, 인증 회원은 `TestLogin.인증된_사용자(...)`·`학교_인증_처리(jdbc, username, 학교명)`. 허가서 테스트 도우미는 `permit/AdminPermitTestBase`.
- 테스트 메일은 `@Primary` 가짜 `VerificationMailSender`. 인증 Redis 키 `email-verify*`, 푸는 법은 deploy.md. (메일 인증은 S20-f에서 걷어낼 예정)
- 로컬 실제 메일: `.env`에 MAIL_* + `MAIL_MODE=smtp`. 화면 확인은 `MAIL_MODE=log` + 서버 로그 `[가짜 메일 발송] ... code=`.
- 관리자는 운영 DB SQL로만 지정(deploy.md), 지정 후 재로그인. 관리자는 자기 허가서 신청을 심사할 수 없다(403) — 관리자 본인 학교는 SQL로.
- **Jackson 3**: 설정 이름 `spring.jackson.datatype.enum.fail-on-numbers-for-enums`, JsonNode는 `asString()`(asText는 폐기 예정).
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173·Redis 6380을 쓰고 있을 수 있다.
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일 + `--data-binary @파일`. psql 한글 SQL도 파일로(운영은 `docker exec -i board-postgres sh -c 'psql ...' < 파일`).
- **운영 테스트 계정 = `wntjrdka`(연세대 인증).** 비밀번호는 사용자만 앎(내가 로그인·입력하지 않음 — 데이터는 SQL 파일로).
- 로컬 DB에는 S16 확인용 테스트 계정·글·중고 글이 남아 있다(로컬 전용, 지워도 됨).
