# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.
자세한 결정·기록은 docs/backlog.md(각 S번호 항목 + "끝난 일" 표), 배포는 docs/deploy.md, API는 docs/api.md.

마지막 갱신: 2026-11-15 집 PC (**S18 운영 챗봇 제미나이 켜기 완료·배포됨, S18-g 정리까지 커밋**)

## 지금 어디까지
- **S0~S14 + UI1~UI3 + S16 + S17 + S18 완료·배포됨.** DB는 **V13까지**(S17·S18은 표 변경 없음). 운영은 2026-11-15 **S18 배포(커밋 2f4e560 이미지)**, 운영 챗봇은 **gemini(무료 등급)로 켜짐**. 배포 후 확인 통과(board-app 375MiB/512MiB).
- 그 뒤 커밋 942a3eb(배포 기록)·**c58d013(S18-g 정리: 화면 "질문은 구글 AI로 전송돼요" 안내 한 줄 등)은 아직 운영에 안 올라감** → 다음 배포(S19) 때 같이.
- **S18(운영 챗봇 제미나이)**: 결정·단계는 backlog S18 항목과 "끝난 일" 표(a~g).
  - `GeminiLlmClient`(RestClient + Map + JsonNode, 수업 DAY15 방식, 새 의존성 0): `POST /v1beta/models/{model}:generateContent`, 키는 헤더 `x-goog-api-key`만, `thinkingLevel: minimal`(3.x 전용), 양식 호출은 `responseJsonSchema`, 호출 전체 상한(별도 스레드 + future.get), 재시도 0, 구글 오류는 전부 우리 503(원인 예외 안 붙임, 로그엔 상태 숫자만).
  - 모델 `gemini-3.5-flash-lite`(3.1은 예비, 2.5 flash-lite는 신규 404·하루 20). 무료 한도 RPM 15·RPD 500(프로젝트 단위, 태평양 자정 리셋). 키는 AI Studio **SpringStudy 프로젝트** 키(수업에서 더 안 씀).
  - 설정: `LLM_MODE=off|ollama|gemini`, 질문 예산 모드별(`GEMINI_TIMEOUT` 30초 / `OLLAMA_TIMEOUT` 60초, `LlmProperties.budget()`), 서버 30 < nginx 75 < 화면 90. 시작 검사(키 빔·공백·제어 글자, 모델 모양, https만).
  - 서버 전체 질문 상한 `ChatGlobalLimiter`: 분 7·하루 200질문(태평양 날짜), 회원 429 뒤·자리 앞, 넘으면 503. 앱 기본 0(끔), **운영 값은 compose 기본값**.
  - 챗봇은 **중고·나눔 글 찾기만**: 물건 이름 없는 질문은 "저는 중고·나눔 글만 찾아 드려요…", 0건은 "관련 글을 찾지 못했어요…". 화면 안내 문구.
  - 운영 서버 `.env`에는 `LLM_MODE=gemini`·`GEMINI_API_KEY` 두 줄만. 끄기·키 교체·되돌리기는 deploy.md "S18 배포 후 확인".
  - 운영에 챗봇 확인용 테스트 중고 글 15개(`docs/sql/chatbot-test-seed.sql`, 지우기 `chatbot-test-cleanup.sql`, 테스트 계정 글·제목 `[테스트]`)가 **남아 있음**(실제 회원 받기 전에 지우기).
- **S16(React → Flutter 웹)**: 끝. 코드 `flutter_app/`(위젯 테스트 1328개, e2e 11개, 손 확인표 `flutter_app/e2e/README.md`).
- **방향**: 학습·포트폴리오 중심. 다음 큰 기능 **S19 챗봇 일반 답변 + 사이트 사용법 안내**(사용자 결정, 후보 A). 그 뒤 후보: S15 글 번역(제미나이 연결 재사용), 비자 후기 검색(DAY15 chat4 방식), 욕설·스팸 검사, 챗봇 뜻 검색, LoRA 실험.

## 다음에 할 일
- **S19 시작**: backlog S19 항목(바꿀 곳 목록·따져 볼 것 포함)을 planner에 넘겨 계획 → 사용자 승인. 시작 전 참고: 일반 답변도 "질문 하나 = AI 호출 최대 2번"을 지켜야 분 7·하루 200 계산이 맞음, `maxOutputTokens` 넣기, 안내 문구(chat_page.dart chatGuide·부제·PageTitle '중고 글 챗봇')·NO_KEYWORD_ANSWER·FORM_PROMPT "인사면 keyword 비워" 바꾸기.
- (선택) 구글 503 빈도 세기: 운영 로그 `grep -c 'gemini 호출 실패: HTTP 503'`를 질문 수와 비교 — 20번에 1번 넘으면 503만·질문당 1번 재시도 + 분 상한 5 검토(backlog S18 파트 리뷰 줄).
- (선택) 상단바 닉네임을 오른쪽 끝(로그아웃 옆)으로(backlog "나중(S18-f)").
- **EC2는 꺼 두기** — S18 배포 확인 뒤 사용자가 중지(켜져 있으면 콘솔에서 중지).
- Notion: S18 카드(a·b·c·d 페이지 + 🧭 파트 전체 정리 + 리뷰 표 "반영함 S18-g"), 도구 사전 Gemini API, 진행 현황 S18 체크·S19 줄, 아키텍처 그림에 Gemini, 개념 노트 "따닥 두 번 클릭 막기" 완료. notion-guide.md에 "큰 기능이 끝나면 🧭 파트 전체 정리" 절이 생김(배포 확인 뒤 파트 리뷰 + 카드 섹션).
- 운영 확인 남은 것(선택): 학교 인증된 두 번째 계정이 생기면 deploy.md "S13·S14 배포 후 확인" 4·5번. e2e 손 확인표.
- (나중, 포트폴리오 트러블슈팅 후보) g6-1, S13-h CI 빨간불, S16 CI e2e 글자 빠짐, S16 화면 겹침, S17 실측 3건, S17 타임아웃 경주, **S18 무료 API 비교·실측으로 모델 고르기**, **S18 리뷰가 찾은 "취소된 호출의 중복 로그"·"헛도는 본문 단언"**.

## 진행 중인 단계
- 없음. S18은 g까지 끝(커밋 c58d013, push 완료). 진행 중인 에이전트 없음.
- 커밋 안 된 변경: `.claude/launch.json`(일부러 커밋 안 함, 집 PC 전용 미리보기 설정).
- 미룬 것: backlog 각 S번호 항목의 "나중(…리뷰)" 줄. 큰 것만:
  - S18: 구글 503 재시도 여부(빈도 먼저), Ollama·Gemini 공통 코드(합치지 않기로, 세 번째 구현 때), 메모리 73% 지켜보기(400MiB 넘으면 원인 확인), Redis volatile-lru 32mb에서 카운터 밀려남, 글쓴이 쪽 "구글로 간다" 안내, 운영 테스트 글 정리.
  - S17: 한 글자 keyword "책"이 책상에도 걸림, 판매 글이 많으면 나눔 글이 5건 밖으로 밀림, 검색이 `LIKE '%…%'` 전체 스캔.
  - S16: 미리보기 cacheWidth, file_picker input 재사용, Safari·VoiceOver 파일 선택, e2e mockApi 타입 검사 없음.
  - S14: 관리자 대화 열람 감사 기록(관리자 늘리기 전 필수), 쪽지 횟수 제한·페이징.
  - S13·S12·S11·S10·S9 이전: backlog 각 항목 참고.
- 운영 서버 메모: EC2 `~/board/.env`에 `PHOTO_S3_BUCKET`·`LLM_MODE=gemini`·`GEMINI_API_KEY`(값은 저장소에 안 씀, 권한 600), compose는 S18-e 버전(옛 파일 `docker-compose.yml.bak-s18`). IMDS 홉 제한 2·IMDSv2 필수·역할 `board-ec2-role`. 직전 배포로 되돌리기 = Deploy #19 `8d8c14d` 이미지, **되돌리기 전에 `.env`의 `LLM_MODE`를 off로 먼저**(옛 이미지는 gemini를 거부해 시작 실패 — deploy.md "S18 되돌리기" 0번).
- 선택 정리: EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **노트북에서 Flutter 작업하려면 준비 2가지**:
  1. Flutter 3.50.0 설치: `git clone --depth 1 -b stable https://github.com/flutter/flutter.git %USERPROFILE%\flutter` → PATH에 `...\flutter\bin` → `flutter doctor`(웹만이라 Chrome ✅면 됨). 첫 실행은 20분 넘게 걸릴 수 있음.
  2. 로컬 `.env`에 `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5175`. 없으면 Flutter 개발 서버에서 로그인 등 POST가 403.
- **Flutter 웹 작업 메모**:
  - 이동은 `context.go`만(router.dart 주석). 모든 경로는 `_page()` = NoTransitionPage. `builder:`로 되돌리면 화면이 0.3초 겹친다.
  - 실제 브라우저 자동 확인: Flutter 입력칸은 `fill()` 대신 "칸 클릭 → 포커스 확인 → `keyboard.type` → `toHaveValue`"(e2e `typeInto`, 챗봇 칸은 라벨 /질문/으로 찾음). 공감 버튼은 role=switch, 에러 안내는 role=alert인데 글자가 aria-label.
  - e2e는 빌드된 build/web을 시험한다: `flutter build web --web-content-hash` 다음 `flutter_app/e2e`에서 `npm test`. 로컬은 Edge, CI는 chromium.
- **제미나이(S18)**:
  - 로컬에서 쓰려면 `.env`에 `GEMINI_API_KEY=`(키 값) + `LLM_MODE=gemini`. **로컬에서 부르면 운영과 같은 무료 한도를 씀**(같은 키). 실제 호출 테스트는 `GEMINI_LIVE_TEST=true`일 때만(`GeminiLiveTests`), 실측 스크립트 `docs/llm-bench-s18.mjs`(결과 `docs/bench-s18-run.json`은 .gitignore).
  - 키는 새 형식 `AQ.`로 시작(예전 `AIza`). 키 누출 검사 패턴은 `x-goog-api-key\|AIza\|AQ\.`. 커밋 전에 `.env`의 실제 키 값으로 `git diff --cached`를 grep해서 0인지 확인해 왔음.
  - 무료 등급은 구글이 바쁘면 가끔 503("gemini 호출 실패: HTTP 503") — 다시 물으면 대개 됨. 키 오류면 401, 없는 모델 404, 한도 429(로그 "한도 초과").
  - 한도 숫자는 문서에 없고 AI Studio → Dashboard → 비율 제한(로그인)에서 "모든 모델" 켜야 보임.
  - Boot 4에서 오류 본문 설정 이름은 `spring.web.error.include-message`(옛 `server.error.*`는 안 먹음).
- **compose가 바뀌는 배포는 Deploy 버튼만으로 안 됨**: Deploy는 이미지만 pull·up. compose를 먼저 백업·scp하고, 새 비밀 값은 서버 `.env`에 nano로 직접(키를 셸 프롬프트에 붙이지 않기, `cat .env` 금지). S10 메일·S13 사진·S18 제미나이 모두 같은 패턴.
- **로컬 LLM(S17)**: 집 PC에 Ollama 0.40.16 + qwen3:8b 등. 실제 Ollama 테스트는 `OLLAMA_LIVE_TEST=true`일 때만. **백엔드 테스트는 Docker Desktop이 켜져 있어야 함**(Testcontainers) — `C:\Program Files\Docker\Docker\Docker Desktop.exe`.
- **Ollama가 GPU를 안 쓰고 CPU로 돌면** `%LOCALAPPDATA%\Ollama\server.log`에 `--list-devices failed: 0xc0000005` → VC++ 재배포 패키지 x64 최신 설치(2026-11-14).
- **Spring AI 함정(ollama 모드)**: 기본 재시도 10회라 끄지 않으면 60초 제한이 수십 분이 됨. Spring 7 `JdkClientHttpRequest` readTimeout은 본문 close까지 재고 도중에 터지면 IOException → 읽기 제한은 전체 상한 + 5초, "시간 초과" 판정은 Future 하나만(Gemini 클라이언트도 같은 방식).
- **집 PC 스마트 앱 컨트롤은 2026-11-13에 끔.** 켜져 있으면 dartvm.exe·oxlint·Playwright 브라우저를 오류 없이 막는다.
- **이 PC엔 `gh` CLI가 없다.** CI 상태는 `curl https://api.github.com/repos/seokam36/board-vibe-coding/actions/runs?head_sha=<40자리 SHA>`(공개 저장소, 비로그인 **시간당 60번** — 2분 간격). 잡별 결과는 `.../actions/runs/<run id>/jobs`.
- **Deploy 전에 그 커밋 CI(images 포함)가 초록인지 확인 필수.**
- **docs 파일을 Git Bash `sed -i`로 고치지 말 것**(줄바꿈 때문에 수십 줄이 바뀐 적 있음). Edit 도구로. 서브에이전트도 파이썬·sed 일괄 치환 금지라고 알려 줌. `cat >> 파일 <<EOF`로 줄을 붙일 땐 **파일 끝에 줄바꿈이 있는지** 먼저 확인(없어서 두 줄이 붙은 적 있음, 2026-11-15).
- **사용자에게 서버 명령을 줄 때 `<서버IP>` 같은 자리표시를 쓰지 말 것.** EC2를 켤 때마다 IP가 바뀌니 사용자에게 IP를 받아 완성 명령을 준다. 사용자 터미널은 PowerShell, 키 경로 `"$HOME\.ssh\board-ec2"`, ssh·scp는 사용자가 직접(키 암호). 서버/내 PC 어디서 칠지 매번 표시.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `V14__add_...sql`, UNIQUE는 `uk_` 관례). 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate. 테스트·더미 데이터는 V 파일에 넣지 않는다(`docs/sql/`처럼 손으로 실행하는 파일).
- **FK·UNIQUE·CHECK 제약에는 반드시 이름**(서비스가 제약 이름으로 404/401/409 구분). V1의 `posts.author_id` FK만 해시 이름.
- **잠금 순서(S14)**: 탈퇴는 회원 행 FOR UPDATE 먼저. 쪽지 쓰기·답장·읽음·대화 신고는 회원 행 FOR KEY SHARE 먼저, 그다음 방·쪽지. 어기면 40P01 교착 → 500.
- **DB 오류 메시지 언어는 영어(기본값) 유지.**
- 경쟁 조건 테스트는 `LockRaceSupport`, 유령 토큰은 `TestLogin.행없는_회원_토큰(jwtEncoder)`, 인증 회원은 `TestLogin.인증된_사용자(...)`·`학교_인증_처리(jdbc, username, 학교명)`.
- 테스트 메일은 `@Primary` 가짜 `VerificationMailSender`. 인증 Redis 키 `email-verify*`, 푸는 법은 deploy.md.
- 로컬 실제 메일: `.env`에 MAIL_* + `MAIL_MODE=smtp`. 화면 확인은 `MAIL_MODE=log` + 서버 로그 `[가짜 메일 발송] ... code=`.
- 관리자는 운영 DB SQL로만 지정(deploy.md), 지정 후 재로그인.
- **Jackson 3**: 설정 이름 `spring.jackson.datatype.enum.fail-on-numbers-for-enums`, JsonNode는 `asString()`(asText는 폐기 예정).
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173·Redis 6380을 쓰고 있을 수 있다.
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일 + `--data-binary @파일`. psql 한글 SQL도 파일로(운영은 `docker exec -i board-postgres sh -c 'psql ...' < 파일`).
- **운영 테스트 계정 = `wntjrdka`(연세대 인증).** 비밀번호는 사용자만 앎(내가 로그인·입력하지 않음 — 데이터는 SQL 파일로).
- 로컬 DB에는 S16 확인용 테스트 계정·글·중고 글이 남아 있다(로컬 전용, 지워도 됨).
