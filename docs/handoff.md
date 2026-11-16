# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.
자세한 결정·기록은 docs/backlog.md(각 S번호 항목 + "끝난 일" 표), 배포는 docs/deploy.md, API는 docs/api.md.

마지막 갱신: 2026-11-16 (**S20 유학생 인증 진행 중: S20-d1·d2·f1·f2·e1·e2·g1 커밋, 다음은 S20-g2 철회·인증 해제 화면**)

## 지금 어디까지
- **S0~S14 + UI1~UI3 + S16 + S17 + S18 완료·배포됨.** 운영은 2026-11-15 S18 배포(커밋 2f4e560 이미지), 운영 챗봇 gemini(무료 등급) 켜짐. 그 뒤 커밋(S18-g 정리, S20-a~g1)은 **아직 운영에 안 올라감** → S20 배포(S20-i) 때 같이.
- **방향(2026-11-16 사용자)**: "외국인 유학생 통합 에타". **유학생만 글을 쓴다** — 표준입학허가서 인증이 입장권. S19(챗봇 일반 답변)·S15 번역·비자 후기 검색·욕설 검사는 기능을 다 만든 뒤로.
- **S20(유학생 인증)** — 결정 전부 backlog S20 항목(각 "S20-? 결정" 줄 + "나중(…리뷰)" 줄 + "알려진 한계"):
  - 서류는 **표준입학허가서**, 확인은 **반자동**(Cloud Vision이 읽고 판정 규칙이 판단, 4관문 다 통과면 자동 승인, 그 밖은 관리자 대기, **자동 반려 없음**). 승인 = `users.verified_school_id` 채우기.
  - 사진은 대기 중에만 보관(S3 `permits/`), 결과 나오면 즉시 삭제, 14일 넘으면 자동 만료. 번호는 HMAC 해시만.
  - 끝난 단계: S20-0 조사 · a 신청 API·V14 · b 관리자 심사 API · c 14일 만료 · **d1 판정 규칙**(e260569, 국적은 "외국 나라 이름 허용 목록(Java Locale)에 있어야 통과") · **d2 신청에 판정 연결 + 자동 승인**(676464b) · **f1 메일 인증 백엔드 제거**(e21e872, API 15·16번 404, spring-boot-starter-mail·MAIL_* 제거, DB 표·칸은 둠) · **f2 /me 메일 폼 제거**(a045777) · **e1 Vision 판독기 클래스**(9583e62) · **e2 모드 스위치 `PERMIT_OCR_MODE=off|vision`**(8f3e524) · **g1 회원 신청 화면**(34c7637, 동의·가림 안내·반려 사유 문구는 backlog "S20-g 결정" 줄이 정본).
  - 백엔드 테스트 약 1839개, Flutter 테스트 1344개, e2e 12개 통과.
- DB는 **V14까지**(새 V 파일 없음. `machine_result` CHECK V15는 S20-h 끝에).

## 다음에 할 일
- **S20-g2 build 시작**(계획·문구 승인 끝, backlog "S20-g 결정" 줄): 대기 중 [신청 철회](확인 창), 인증 완료 [인증 해제](경고 확인 창 — 문구 정해짐), DELETE 204 뒤 refreshUser, 404·403·401 문구. g1 리뷰 메모: 해제 직후 신청 폼이 보이는지 테스트.
- 그다음: g3 관리자 "인증 심사"(`/admin/permits`) 목록·사진 보기(dio 바이트 + Image.memory, 행 아래 펼치기) → g4 승인·반려(둘 다 확인 창, 반려 사유에서 EXPIRED 숨김, 본인 신청은 버튼 대신 안내) → g5 다른 화면 8곳 "학교 인증" → "유학생 인증" 문구 → **사용자 카드 등록 뒤** S20-h 실물 시험 → S20-i 배포. **S20-g 전에는 배포 금지**(다른 화면 "학교 인증하기" 링크가 막다른 길).
- **사용자 할 일(카드 등록, S20-h 전)**: Google Cloud에 **새 프로젝트**(예: board-vision) → 그 프로젝트에만 결제 계정 연결 → Cloud Vision API 사용 설정 → API 키 만들고 "API 제한"을 **Cloud Vision API만** → 결제 "예산 및 알림"에서 예산 + Vision 하루 요청 할당량(예: 30). 가격 숫자는 공식 가격 페이지에서 직접 확인. **Gemini 키가 있는 SpringStudy 프로젝트에는 결제를 붙이지 말 것.**
- **S20-h에서 할 것**: `VISION_LIVE_TEST=true` + `VISION_API_KEY`로 `VisionLiveTests` 실행(X-Goog-FieldMask가 먹는지), 견본 3장을 진짜 Vision으로 읽어 줄 순서 확인 → 판정 규칙 보정, 언어 힌트 ko·en vs 없음 비교, 신청 2분 제한(웹은 업로드 포함)을 5분으로 늘릴지, 사진 한 건 순간 메모리. 실물 사진은 저장소에 넣지 않는다.
- **S20-i 배포 때 할 것**: 서버 `.env`에 `PERMIT_HASH_SECRET`(32바이트 이상, 한 번 정하면 안 바꿈) + `VISION_API_KEY` + `PERMIT_OCR_MODE`(처음엔 off, g 화면 확인 뒤 vision), compose app에 `PERMIT_HASH_SECRET`·`PERMIT_OCR_MODE: ${PERMIT_OCR_MODE:-off}`·`VISION_API_KEY`·`VISION_TIMEOUT: ${VISION_TIMEOUT:-10s}` 줄(deploy 담당, `:-`로 빈 값 방지)과 MAIL_* 줄 삭제, **S3 버킷 `permits/` 접두사에 14일 수명 주기 규칙**("모든 객체" 고르면 중고 사진까지 지워짐 주의), S3 기본 암호화 확인, deploy.md 메일 절 삭제·S20 되돌리기 절(V14 생김), 배포 확인 뒤 서버 `.env`의 MAIL_* 삭제 + **Gmail 앱 비밀번호 폐기**. JVM 옵션 `-Djdk.httpclient.HttpClient.log=headers`는 키를 찍으니 켜지 말 것.
- Notion: S20 카드에 d1·d2·f1·e1·e2 단계 페이지와 "흐름 설명: 허가서 사진 삭제" 페이지, 단계 표 g1까지. 화면 단계(f2·g*)는 표 한 줄만. S20 끝나면 🧭 파트 전체 정리.
- (선택, 예전부터) 구글 503 빈도 세기, 상단바 닉네임 위치, 운영 테스트 중고 글 15개는 실제 회원 받기 전에 지우기, EC2는 꺼 두기.

## 진행 중인 단계
- **S20 진행 중**: g1까지 build·tester·review·커밋 끝. g2는 아직 시작 전(계획·문구는 승인됨). 진행 중인 에이전트 없음.
- 커밋 안 된 변경: `.claude/launch.json`(일부러 커밋 안 함), `frontend/`(예전 React 찌꺼기, 커밋하지 말 것).
- 미룬 것: backlog S20 항목의 "나중(S20-? 리뷰)" 줄들 — S20 파트 리뷰·S20-h·S20-i에서 챙길 것.
- 운영 서버 메모: EC2 `~/board/.env`에 `PHOTO_S3_BUCKET`·`LLM_MODE=gemini`·`GEMINI_API_KEY`·MAIL_*(값은 저장소에 안 씀, 권한 600), compose는 S18-e 버전. IMDS 홉 제한 2·IMDSv2 필수·역할 `board-ec2-role`. 직전 배포로 되돌리기 = Deploy #19 `8d8c14d` 이미지, **되돌리기 전에 `.env`의 `LLM_MODE`를 off로 먼저**(deploy.md "S18 되돌리기" 0번). S20 배포 뒤 되돌리기는 V14가 생기므로 deploy.md에 새로 적어야 함.
- 선택 정리: EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **로컬에서 앱(bootRun)을 켜려면 `PERMIT_HASH_SECRET`이 필요하다.** 비거나 32바이트 미만이면 시작 실패. 만드는 법은 `.env.example`. 테스트는 설정 파일의 랜덤 값을 써서 필요 없음.
- **`PERMIT_OCR_MODE`(S20-e2)**: 기본 off(판독기 "못 읽음" → 전부 관리자 대기). vision이면 `VISION_API_KEY` 필수, 키 공백·줄바꿈·https 아닌 주소·모르는/빈 모드 값(`VISION`, ` vision `도)이면 **앱 시작 실패**(메시지에 값 없음). 테스트 설정은 off 고정. 실제 구글 호출 테스트는 `VISION_LIVE_TEST=true` + 키일 때만.
- **허가서(S20) 개인정보 규칙**: 로그에 사진 key·`permits/`·허가서 번호·해시·판독 글자·Vision 키를 남기지 않는다(`PhotoDeleteQueue`가 permits/ key를 "허가서 사진 N건"으로 가림, Vision 판독기는 상태 숫자·예외 이름·error.code만). `logServerErrorDetail=false`로 PostgreSQL 오류 Detail이 로그에 안 나감. 테스트 값은 지어낸 것만(실물 허가서 이름·번호 금지).
- **판정 규칙 함정(S20-d1)**: 국적을 "한국 말이 없으면 통과"(블랙리스트)로 하면 값을 엉뚱하게 집을 때 통과한다 → 허용 목록 방식 유지. 견본 라벨: `국적(Country of citizenship)`(옆 칸 `출생국가`와 구분), `학교명(School name)`, `표준입학허가서 번호(Certificate of Admission No.)`(`입학허가번호`는 다른 칸). 위조는 못 막음(알려진 한계).
- **자동 승인(S20-d2)**: 판독·판정은 PENDING 커밋 뒤 트랜잭션 밖, `processAndStore`의 try/catch 밖(안이면 커밋된 사진을 지움). 승인은 `AdminPermitService.lockPending` 재사용.
- **주기 작업은 cron 대신 fixedDelay + initialDelay**(EC2를 평소 꺼 두므로). 사진 청소부(10분)·허가서 만료(기동 3분 뒤 + 1시간). 테스트는 `app.scheduling.enabled=false`라 `runOnce`를 직접 부른다.
- **서브에이전트가 끝난 뒤에도 같은 보고를 반복하며 뒤에서 gradle을 돌리면** 다음 tester의 gradle과 겹칠 수 있다 → TaskStop으로 멈춘다(파일 결과는 남음). 프롬프트에 "뒤에서 도는 작업 남기지 말 것"을 넣어 둠.
- **Notion 표 함정**: 표 칸 글자에 `|`를 쓰면 칸이 잘린다(예: "off|vision" → "off 또는 vision"). update_content의 old_str이 앞부분만 겹치면 엉뚱한 줄이 바뀌니 바꾸기 전에 fetch로 확인.
- **IDE(VS Code Java)의 "getId() undefined"·"log cannot be resolved" 진단은 무시**: Lombok을 IDE가 못 읽는 것뿐.
- **Flutter 작업 준비**: Flutter 3.50.0이 PATH에 없으면 `%USERPROFILE%\flutter\bin`(Git Bash에선 `/c/Users/seoka/flutter/bin`)을 PATH에. 없으면 `git clone --depth 1 -b stable https://github.com/flutter/flutter.git %USERPROFILE%\flutter` → `flutter doctor`(웹만이라 Chrome ✅면 됨). 로컬 `.env`에 `CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5175`(없으면 Flutter 개발 서버에서 POST가 403).
- **Flutter 웹 작업 메모**:
  - 이동은 `context.go`만. 모든 경로는 `_page()` = NoTransitionPage.
  - FormData 요청은 생성 함수를 `Options(extra: {ApiClient.formDataFactoryKey: make})`에 꼭 넣는다(401 재시도 때 본문을 새로 만듦). 공용 dio엔 시간 제한이 없어 요청마다 receiveTimeout을 건다(웹에선 업로드 포함 전체 시간).
  - 파일 고르기는 직접 만든 `photoPicker`(`pick(onPicked, {multiple})`), 허용 종류는 `allowedPhotoTypes`, 상한은 `maxPhotoBytes` 공용.
  - 테스트 `pumpApp`은 미인증 회원이 `/me`로 시작하면 상태 GET 기본 응답(NONE)을 깔아 준다(다른 주소에서 /me로 이동하는 테스트는 직접 준비).
  - 실제 브라우저 자동 확인: Flutter 입력칸은 "칸 클릭 → 포커스 확인 → `keyboard.type` → `toHaveValue`"(e2e `typeInto`). e2e는 빌드된 build/web을 시험한다: `flutter build web --web-content-hash` 다음 `flutter_app/e2e`에서 `npm test`. 기본 응답에 `GET /users/me/permit-verification`(NONE) 있음.
- **제미나이(S18)**: 로컬에서 쓰려면 `.env`에 `GEMINI_API_KEY` + `LLM_MODE=gemini`(운영과 같은 무료 한도). 키는 `AQ.`로 시작. 키 누출 검사 패턴은 `x-goog-api-key\|AIza\|AQ\.` — 커밋 전에 `git diff --cached`를 grep해서 0인지 확인해 왔음(테스트용 가짜 키 문자열은 걸릴 수 있으니 실제 키 형식으로 확인). 무료 등급은 가끔 503.
- **compose가 바뀌는 배포는 Deploy 버튼만으로 안 됨**: compose를 먼저 백업·scp하고, 새 비밀 값은 서버 `.env`에 nano로 직접(키를 셸 프롬프트에 붙이지 않기, `cat .env` 금지).
- **로컬 LLM(S17)**: 실제 Ollama 테스트는 `OLLAMA_LIVE_TEST=true`일 때만. **백엔드 테스트는 Docker Desktop이 켜져 있어야 함**(Testcontainers).
- **Spring AI 함정(ollama 모드)**: 기본 재시도 10회라 끄지 않으면 60초 제한이 수십 분이 됨. 외부 호출 시간 제한은 "Future 하나로 전체 상한"(Gemini·Vision 같은 방식).
- **집 PC 스마트 앱 컨트롤은 2026-11-13에 끔.** 켜져 있으면 dartvm.exe·oxlint·Playwright 브라우저를 오류 없이 막는다.
- **이 PC엔 `gh` CLI가 없다.** CI 상태는 `curl https://api.github.com/repos/seokam36/board-vibe-coding/actions/runs?head_sha=<40자리 SHA>`(비로그인 시간당 60번). **Deploy 전에 그 커밋 CI(images 포함)가 초록인지 확인 필수.**
- **docs 파일을 Git Bash `sed -i`로 고치지 말 것**(줄바꿈 때문에 수십 줄이 바뀐 적 있음). Edit 도구로. backlog는 CRLF라 끝에 줄을 붙일 땐 `printf '%s\r\n'`.
- **사용자에게 서버 명령을 줄 때 `<서버IP>` 같은 자리표시를 쓰지 말 것.** 사용자에게 IP를 받아 완성 명령을 준다. 사용자 터미널은 PowerShell, 키 경로 `"$HOME\.ssh\board-ec2"`, ssh·scp는 사용자가 직접.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `V15__...sql`). 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate(엔티티에 있는 것만 봄 — 안 쓰는 표가 DB에 남아도 됨).
- **FK·UNIQUE·CHECK 제약에는 반드시 이름**(서비스가 제약 이름으로 404/401/409 구분).
- **잠금 순서(S14)**: 탈퇴는 회원 행 FOR UPDATE 먼저. 쪽지 쓰기·답장·읽음·대화 신고는 회원 행 FOR KEY SHARE 먼저. **허가서(S20)**: 승인·자동 승인·반려·만료·철회·탈퇴 모두 신청자 회원 행 FOR UPDATE 먼저 → 신청 행 → 상태 재확인(`lockPending`). 신청(POST)은 회원 행 FOR KEY SHARE.
- **사진 대기 표(photo_delete_queue) 함정**: 청소부는 표의 key 중 `market_item_photos`가 안 쓰는 것을 지운다. 허가서 사진은 "저장 전에 적고 INSERT 트랜잭션에서 떼기"(신청), "결정·삭제 트랜잭션 안에서 적고 커밋 뒤 지우기"(승인·반려·만료·철회·탈퇴)만 쓴다.
- **DB 오류 메시지 언어는 영어(기본값) 유지.**
- 경쟁 조건 테스트는 `LockRaceSupport`, 유령 토큰은 `TestLogin.행없는_회원_토큰(jwtEncoder)`, 인증 회원은 `TestLogin.인증된_사용자(...)`·`학교_인증_처리(jdbc, username, 학교명)`(JDBC로 칸을 직접 채움). 허가서 테스트 도우미는 `permit/AdminPermitTestBase`, 가짜 판독기는 `permit/FakePermitTextReader`(@Primary).
- 관리자는 운영 DB SQL로만 지정(deploy.md), 지정 후 재로그인. 관리자는 자기 허가서 신청을 심사할 수 없다(403) — 관리자 본인 학교는 SQL로.
- **Jackson 3**: JsonNode는 `asString()`·`isString()`.
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173·Redis 6380을 쓰고 있을 수 있다.
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일 + `--data-binary @파일`.
- **운영 테스트 계정 = `wntjrdka`(연세대 인증, SQL로).** 비밀번호는 사용자만 앎. S20 배포 뒤에도 인증 칸이 있어 글쓰기 그대로(GET 상태는 APPROVED). [인증 해제]를 누르면 되돌릴 방법이 SQL이나 허가서 신청뿐.
- 로컬 DB에는 S16 확인용 테스트 계정·글·중고 글이 남아 있다(로컬 전용).
