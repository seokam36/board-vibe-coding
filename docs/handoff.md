# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-11 (S13-a~d 끝냄)

## 지금 어디까지
- **S0~S12 + UI1~UI3 완료·배포됨.** 운영은 S12-e(2026-11-11) 이미지, V8까지 적용. S12 리뷰 수정(21741e0)은 push됐지만 운영 미반영(다음 배포에 같이 나감).
- **방향 결정(2026-11-11):** 이 프로젝트는 학습·포트폴리오(A)가 중심, 실제 사용자(B)는 되면 좋은 것. 비자 일정 캘린더(.ics)는 보류. 다음 큰 기능은 **S13 중고거래·나눔 → S14 쪽지 → S15 글 번역**. **S13과 S14를 다 만든 뒤 한 번에 배포**(쪽지가 없으면 연락 수단이 없어서).
- **S13(중고거래·나눔) a~d 완료, push됨(f8071ad), 운영 미반영.** 결정 전부 backlog S13 항목.
  - S13-a: V9 `market_items`(판매자 FK ON DELETE CASCADE, CHECK 거래 방식·상태·가격(나눔 0 / 판매 1~10,000,000)) + `POST /market-items`(학교 인증 회원, 학교는 인증 학교 복사) · `GET /market-items/{id}`(공개).
  - S13-b: `GET /market-items`(공개, schoolId·tradeType·status 필터) · `PUT /{id}`(본인만, 관리자도 403) · `DELETE /{id}`(본인·관리자).
  - S13-c: `PUT /{id}/status`(본인만). 전환 규칙은 `ItemStatus.canChangeTo` 한 곳(ON_SALE↔RESERVED, →DONE, DONE에서 못 나감). 상태 변경·내용 수정 모두 **조건부 UPDATE**(where status=from / status<>DONE), 0행이면 existsById로 404/409. status는 `updatable=false`, 엔티티에 내용·상태 바꾸는 메서드 없음(일부러). 완료 글은 내용 수정 409, 삭제만.
  - S13-d: 화면 `/market`·`/market/:id`·`/market/new`·`/market/:id/edit`, 상단바 "중고·나눔". 판매자만 갈 수 있는 상태 버튼, [거래 완료] 확인 창, 관리자는 남의 글에 [삭제]만.
- Notion: S13 카드(번호 14 "중고거래·나눔 게시판")에 S13-a·b·c·d 단계 페이지, 진행 현황 S13 줄(+S14·S15) 있음.

## 다음에 할 일
- **S13-e 신고 연결** 시작. 규칙대로 build 전에 무엇을 만드는지 쉬운 말로 설명하고 OK 받기. 내용(planner 계획): `POST /market-items/{id}/reports`, 관리자 신고 목록에 MARKET_ITEM, **V10**으로 `reports`에 `market_item_id`(FK CASCADE, UNIQUE) 추가 + `ck_reports_target`을 "셋 중 정확히 하나"로 교체. 다른 학교 글 신고 막을지는 사용자 확인.
- 그다음 S13-f 제목 검색 → S13-g 사진(research 먼저: 저장 위치 EC2 디스크 vs S3, nginx `client_max_body_size`, 백업에 사진 없음) → S14 쪽지 → 배포.

## 진행 중인 단계
- 없음(S13-d까지 커밋·push 끝). 커밋 안 된 변경: `.claude/launch.json`(컴퓨터마다 다른 실행 설정, 일부러 커밋 안 함).
- 미룬 것(S13): backlog S13 항목의 "나중(S13-a 리뷰)"·"나중(S13-b 리뷰)"·"나중(S13-c 리뷰)"·"나중(S13-d 리뷰)" 줄(가격 5000.9·"5000" 문자열 받아짐 확인, 비자 후기 수정 중 삭제 500, 학교별 머리말, 가격 굵기 등).
- 미룬 것(S12): backlog S12 항목의 "나중(S12-c 리뷰)"·"나중(S12-d 리뷰)" 줄 · S12-a 리뷰: 배열·숫자 날짜(`[2026,12,31]`, `20818`)도 LocalDate로 받아짐(후기 날짜와 함께 Jackson 설정 한 곳에서 막기) · upsert `@Modifying`에 clearAutomatically 없음(upsert 뒤 다시 읽는 코드가 생기면) · "오늘 이후여야 합니다" 400 문구(오늘도 허용) · 덮어쓰기 때 created_at 유지 단언 · FlywayExistingBaselineTests가 V8 성공 행을 직접 단언하지 않음 · S12-b: 계산 로직이 DTO(VisaPlanResponse.of) 안(두 번째 사용처 생기면 domain으로) · 띠 자정 넘김 자동 갱신 없음(새로고침하면 반영)
- 미룬 것(UI3 리뷰): "본인 글/댓글/후기만 수정할 수 있습니다" 3형제를 함수 하나로(라벨만 다른 문구는 함수형 사전 허용으로 원칙 정하기) · errorMessage.ts 맨 위 파일 안내 주석 · SignupPage '가입하지 못했습니다' 2번 지역 상수 · 문구 자체 변경(CSRF 403과 학교 인증 403 섞임, 어미 통일, 학교 인증 403 변형 3종, 수정 403의 CSRF 미고려) — backlog UI3 "나중"
- 미룬 것(UI2 리뷰): CI e2e 리포트가 실패 때만 올라가 재시도로 통과한 불안정 테스트는 안 보임(`if: !cancelled()` 후보) · Chromium 캐시 · 관리자 남의 글 삭제 장면이 `.first()`(글 삭제 버튼이 댓글보다 먼저라는 DOM 순서에 기댐)
- 미룬 것(S11 파트 리뷰 "나중"): 날짜를 숫자·배열로 보내도 Jackson이 받아 줌(S12-a에서 확인됨, 위 S12 항목과 함께 처리) · 1건짜리 통계도 "평균"으로 보임(화면은 건수 같이 표시) · 한 회원 중복 작성은 backlog 알려진 한계
- 미룬 것(S11-e 리뷰): 목록·통계 화면의 요청·에러 문구 중복을 훅으로(세 번째 사용처 생길 때) · 탭이 주소 쿼리를 통째로 붙임(쿼리 늘면 필터 3개만) · PostCreatePage·VisaReviewCreatePage의 같은 6줄 → useVerifiedSchool 훅 · 상세에서 삭제 후 목록으로 갈 때 필터 사라짐(글도 같음) · "목록으로"도 필터 안 들고 감
- 미룬 것(S11 리뷰): 후기 수정 중 다른 쪽이 삭제하면 500(글 수정과 같은 한계) · 테스트의 `@MockitoBean Clock`이 컨텍스트를 따로 띄움(느려지면 공용 MutableClock) · RecordingSender를 공용 테스트 설정으로 · SecurityConfig `/visa-reviews/stats` 명시 matcher는 `{id}` 규칙과 중복(의도 표시용)
- 미룬 것(S10 리뷰 "나중"): 메일 해시가 소금 없는 SHA-256(Redis·DB 털리면 사전 역산 가능 → 나중에 HMAC로 둘 다) · Redis Cluster로 가면 인증 Lua 키(5개)가 한 슬롯에 안 묶임 · 인증 테스트 헬퍼(RecordingSender·sha256) 중복 · 409 "다른 계정이 쓴 메일"이 존재 여부를 조금 알려 줌 · Gmail 하루 약 500통 한도(근본 해결은 도메인 + AWS SES) · SMTP 장애 중 연타하면 요청마다 최대 ~25초 스레드 점유 · 운영 gmail.com 임시 도메인 테스트 뒤 그 시간대 인증 계정 수 확인 단계 · 인증 설정값 @Validated · api.md 400·410 문구는 실제 본문에 없음 · 운영 MAIL_MODE 빠지면 조용히 log 모드
- 미룬 것(S9 리뷰 "나중"): 관리자 목록 페이징 · 기각된 신고는 같은 사람이 재신고 불가 · 관리자 삭제로 처리하면 신고 이력 없음 · 서버 에러 message가 브라우저에 안 감(include-message never, 화면은 상태코드로 문구 — S10도 이 전제로 400/410 등으로 나눔) · SecurityConfig `BearerTokenResolver` deprecated 경고 · JPQL enum 전체 경로 리터럴 · `ReportResponse.ReasonInfo` 중복 DTO · 유령 토큰의 글·댓글 수정은 401이 아니라 403.
- 미룬 것(UI1 리뷰): (CSRF 403 문구 섞임은 backlog UI3 "나중"으로 옮김) · /admin/reports 비로그인 시 /login 대신 안내만 · Google Fonts 대신 셀프 호스팅.
- 미룬 것(S8 이전): 글 목록 페이징 + 목록 응답에서 본문 빼기 · 댓글 수정 중 글이 CASCADE로 사라지면 500 · `Location: /comments/{id}`인데 단건 GET 없음 · 학교·말머리 목록 중복 코드 훅으로 · `.gitattributes` 줄바꿈 규칙(서브에이전트가 LF로 저장해 CRLF 경고가 계속 뜸, 내용엔 영향 없음).
- 선택 정리(급하지 않음): EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `V10__...sql`)로. 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate 유지.
- **FK·UNIQUE·CHECK 제약에는 반드시 이름**을 지어라. 서비스가 제약 이름으로 404/401/409를 구분한다(`ConstraintViolations`). V1의 `posts.author_id` FK만 해시 이름이라 글쓰기는 "23503이면 401".
- **DB 오류 메시지 언어는 영어(기본값) 유지.** `lc_messages`를 바꾸면 경쟁 상황에서 500(docs/deploy.md).
- 경쟁 조건 테스트는 `LockRaceSupport`, 유령 토큰은 `TestLogin.행없는_회원_토큰(jwtEncoder)`, 인증 회원은 `TestLogin.인증된_사용자(...)`·`학교_인증_처리(jdbc, username, 학교명)`. 글쓰기·댓글·공감·신고 테스트는 **인증 회원 + 같은 학교 글**이어야 한다.
- 테스트에서 메일 발송 결과를 보려면 `@Primary` 가짜 `VerificationMailSender`(SchoolEmailCodeApiTests의 RecordingSender 참고). 인증 Redis 키는 `email-verify*`(코드·cooldown·daily·pair·target), 테스트 전후 정리.
- 인증 관련 Redis 키 이름·막혔을 때 운영자가 푸는 법은 docs/deploy.md "학교 인증 코드가 막혔다는 문의가 오면".
- 로컬에서 실제 메일 확인하려면 `.env`에 MAIL_* + `MAIL_MODE=smtp`. 학교 도메인만 허용이라 개인 Gmail로 받으려면 **로컬 DB에만** `insert into school_email_domains (school_id, domain) select id,'gmail.com' from schools where name='연세대학교';` 후 테스트, 끝나면 delete. 화면 확인은 `MAIL_MODE=log`로 켜고 서버 로그의 `[가짜 메일 발송] ... code=`를 읽는다.
- 옛 이미지로 되돌리기: V3~V7은 칸 추가(NULL/DEFAULT)·새 표만이라 되돌려도 뜬다(docs/deploy.md (b)). 단 V7 후 S10 이미지면 **후기 쓴 회원 탈퇴가 500**. V2 되돌리기 주의도 같은 곳.
- 관리자는 운영 DB SQL로만 지정(docs/deploy.md "관리자 지정·해제"). 지정 후 다시 로그인해야 토큰에 반영.
- **Jackson 3 설정 이름 함정**: 숫자 enum 막기는 `spring.jackson.datatype.enum.fail-on-numbers-for-enums`(옛 이름이면 앱이 안 뜸).
- 집 PC PowerShell은 `npm.ps1`을 막는다. `.claude/launch.json`의 frontend는 `npm.cmd`. 노트북 launch.json의 powershell은 전체 경로(`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`)여야 preview가 찾는다.
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173을 쓰고 있을 수 있다(물어보고 끄거나 다른 포트).
- 백엔드를 끈 채 화면을 열면 Vite 프록시 502 → 각 화면의 기본 실패 문구(정상).
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일로 만들어 `--data-binary @파일`로 보낸다. psql에 한글 SQL도 파일로(`docker cp` 후 `-f`).
- PostgreSQL 컨테이너의 정렬이 한글 가나다순과 달라 학교 이름 순서는 서버 코드에서 정렬.
- **운영 테스트 계정 = `wntjrdka`(연세대 인증, 2026-11-10 SQL로 지정).** 배포 후 인증 회원이 필요한 확인은 이 계정으로 로그인만 하면 된다(가입·메일 인증 반복 X, 비밀번호는 사용자만 앎). 테스트로 쓴 글·후기는 확인 뒤 삭제해 통계 오염 방지. 다른 계정을 인증시키려면 [서버] `UPDATE users SET verified_school_id = (SELECT id FROM schools WHERE name = '연세대학교'), verified_at = now() WHERE username = '...';` 후 재로그인.
- 실제 메일 인증 흐름까지 운영에서 봐야 할 때만(S10-h 방법): gmail.com을 잠깐 연세대 도메인으로 INSERT → 인증 → **꼭 DELETE**. 그 사이엔 누구나 gmail로 연세대 인증 가능하니 짧게.
- 글쓰기 화면은 학교를 고르지 않고 인증한 학교로 고정(S10-f 설계). "다른 학교 글쓰기 막힘"은 화면에서 확인 불가, 테스트가 지킨다.
- 집 PC 로컬 DB: V8까지 적용됨. 연습 계정 `visa_me`(경희대 인증)·`visa_other`(고려대 인증)·`visa_new`(미인증)·`visa_admin`(관리자)·`visa_plan01`(경희대 인증, 비자 일정 2026-12-08 등록됨)과 연습 후기 몇 개가 있다(로컬 전용). 비밀번호는 세션 임시 폴더에만 적어 둬서 사라짐 → 필요하면 새로 가입 후 SQL로 `verified_school_id`·`role` 지정. 회원가입 API는 CSRF 필요(`GET /csrf` 쿠키 → `X-XSRF-TOKEN` 헤더).
- 브라우저 확인 요령: 로그인 풀림은 페이지 JS로 `/api/auth/logout`을 먼저 부르고 저장을 누르면 재현된다. 콘솔 에러는 탭에 계속 쌓이니 파일 버전(`?t=` 숫자)으로 옛 기록인지 구분.
- **비자 후기 "오늘"은 `Clock` 빈(ClockConfig, Asia/Seoul) 기준.** 서버는 UTC라 `LocalDate.now()`·`@PastOrPresent`를 쓰면 서울 밤에 오늘이 미래로 판정된다. 후기 테스트는 `@MockitoBean Clock`으로 서울 2026-11-09 12:00 고정(VisaReviewApiTests·VisaReviewListUpdateDeleteTests와 같은 설정을 써야 컨텍스트를 같이 씀).
- 후기 날짜 규칙: 결과일 ≥ 접수일, 둘 다 ≤ 서울 오늘, **접수일 ≥ 오늘-5년, 처리 ≤ 365일**(테스트 시계 기준 5년 경계 = 2021-11-09).
- **users를 가리키는 FK를 새로 만들면** `UsersForeignKeyGuardTests`가 실패한다(의도). ON DELETE를 주거나 `UserService.withdraw`에 clearAuthor를 추가하고 테스트의 허용 목록도 갱신.
- 후기 작성 테스트는 **인증 회원**이어야 한다(`TestLogin.인증된_사용자`·`학교_인증_처리`). 수정·삭제는 인증 불필요. 탈퇴는 `UserService.withdraw`가 posts·comments·visa_reviews 작성자를 비운 뒤 회원 삭제(작성자 FK에 ON DELETE 없음).
- SecurityConfig의 `GET /visa-reviews/{id}` 공개 규칙은 `/visa-reviews/` 아래 한 칸짜리 GET을 전부 공개로 만든다(`/stats`도 명시해 둠). 로그인 전용 GET을 그 아래 두려면 따로 막아야 한다.
- **Vite 옛 파일 문제가 이번에도 여러 번 났다**(서브에이전트가 고친 직후 화면이 하얗게 비고 `xxx is not defined`). build가 프론트를 고친 뒤에는 브라우저 확인 전에 frontend 서버를 항상 껐다 켠다.
- Flyway 테스트 `FlywayExistingBaselineTests`는 "새로 적용되는 V 파일 수"를 하드코딩한다(지금 8개·기록장 9행) → V10을 만들면 같이 고쳐야 한다.
- **tester가 이제 `frontend/e2e/`에도 쓸 수 있다**(`.claude/agents/tester.md` 허용 경로, 2026-11-10). 에이전트 설정은 세션 시작 때만 읽혀서, 바꾼 그 세션에서는 반영 안 됐다(그래서 S12-c·d e2e는 메인 대화가 씀). 화면 코드 `frontend/src/`는 여전히 못 씀.
- **build 보고를 그대로 믿지 말 것**: S12-c에서 build가 "npm run build 통과"라고 했는데 실제로는 e2e 파일 import 확장자(`'./mockApi'` → `'./mockApi.ts'`)로 tsc가 실패했다. 커밋 전에 메인이 `npm test`·`npm run build`·`npm run lint`·`npm run test:e2e`를 직접 한 번 돌린다.
- **e2e에서 날짜를 입력하는 장면은 브라우저 시계를 고정**한다: `test.beforeEach(async ({ page }) => { await page.clock.setFixedTime(new Date('2026-11-10T03:00:00Z')) })`(visa-plan·visa-banner spec). 날짜 칸에 `min=오늘`이 있어서 고정 안 하면 날짜가 지나면서 테스트가 깨진다.
- e2e 도우미: `loginVerified(api)`는 이제 `e2e/mockApi.ts`에서 export(두 spec 중복 제거). mockApi 기본 응답에 `/users/me/visa-plan` GET·PUT(일정 없음)·DELETE(204)가 있다. 인증 회원으로 로그인하는 모든 장면에서 보관소가 GET을 부른다.
- 테스트가 진짜로 지키는지 볼 때는 고친 줄을 잠깐 바꿔 실패를 확인한다(S12에서 여러 번 함). 원본은 꼭 임시 폴더에 복사해 두고 복사본으로 되돌린다(`git checkout --` 금지).
- 노트북 로컬 DB: 이제 기록장이 있고 V6까지 적용됨. 연습 계정 `mailtest01`(연세대 인증), `koreauser`(고려대 인증, 가짜 해시), `newbie`(미인증), 비밀번호는 연습용. 글 3개(연세대·고려대·학교 미지정).
- `.env`는 git에 없다. `.env.example`을 복사해 채운다. 필요한 키: DB_*, POSTGRES_*, JWT_SECRET, REDIS_PASSWORD, (선택) MAIL_*. **`.env`는 캡처해서 공유하지 말 것.**
- 로컬 `docker compose up -d`는 postgres + redis만(app은 `profiles: prod`). Docker Desktop이 꺼져 있으면 먼저 켠다. **꺼진 채 `./gradlew test`를 돌리면 Testcontainers가 못 떠서 테스트 대부분(650개+)이 컨텍스트 로딩 실패**한다(코드 문제 아님). Docker Desktop 위치는 PC마다 다르다: 한 PC는 `%LOCALAPPDATA%\Programs\DockerDesktop\Docker Desktop.exe`, 집 PC는 `C:\Program Files\Docker\Docker\Docker Desktop.exe`.
- `./gradlew bootRun`은 `.env`를 자동으로 읽지 않는다. PowerShell에서 환경변수로 넣고 실행.
- 프론트 로컬 실행: `frontend`에서 `npm install` 후 `npm run dev` → http://localhost:5173(반드시 5173으로).
- **프론트 테스트**: 단위 `npm test`(Vitest 5.1, `src/**/*.test.ts`만, node 환경) / 브라우저 `npm run test:e2e`(Playwright 1.65, `e2e/*.spec.ts`만, Vite를 **5174** `--strictPort`로 직접 띄움, 백엔드 불필요). 서버 응답은 `e2e/mockApi.ts` 가짜 응답: 테스트에서 `api.override({'GET /posts': {status: 500}})`·`api.setLoggedIn(true)`로 바꾸고, **준비 안 한 /api 요청이 있으면 테스트 실패**(새 화면이 새 주소를 부르면 mockApi에 추가). 새 PC는 `npx playwright install chromium` 한 번(수백 MB). `tsconfig.node.json`이 e2e도 타입 검사해서 e2e 타입 오류는 `npm run build`·Docker 이미지 빌드도 막는다(가짜 데이터가 types.ts와 어긋나면 잡으려는 의도). 도우미: `minePost()`(작성자가 로그인 사용자인 글), `loginVerified(api)`(학교 인증 회원으로 로그인). 에러 문구는 `getByRole('alert')` + `toHaveText('전체 문구')`로 검사(부분 일치 금지).
- **에러 문구를 고치거나 새로 쓸 때**: 2곳 이상 같은 글자면 `errorMessage.ts`의 `ERROR_MESSAGES`에 넣고 `errorMessage.test.ts`의 사전 전체 `toEqual`에도 한 줄 추가(안 하면 테스트 실패). 문구 글자를 바꾸면 `e2e/error-messages.spec.ts` 기대 문구도 같이 바꿔야 CI가 통과한다.
- **Windows 보안 정책(앱 제어)이 내려받은 실행 파일을 막는 PC가 있다**(UI2 시작한 PC): `npm run lint`와 Playwright가 내려받은 chrome-headless-shell이 `spawn UNKNOWN`. 우회하지 말 것. 그 PC는 lint를 CI에서 확인, Playwright는 로컬에서 정식 Edge(`channel: 'msedge'`, `PW_CHANNEL=chrome`으로 변경 가능), CI는 기본 Chromium. **UI2·UI3를 끝낸 PC는 막히지 않음**(lint 정상, `CI=true`로 내려받은 Chromium도 실행됨). 새 PC는 `npm install` 후 `npx playwright install chromium` 한 번.
- **커밋 안 된 파일을 테스트로 일부러 망가뜨릴 때 `git checkout --`로 되돌리지 말 것**(커밋 안 된 작업까지 날아감). 먼저 임시 폴더에 복사해 두고 그 복사본으로 되돌린다.
- 프론트만 바꾸는 단계는 `refactor:`가 아니라 CLAUDE.md 형식(feat/fix/test/chore) 중 하나로 커밋(2adf68f만 `refactor:`로 잘못 달림).
- 자동 보안 검사(security-guidance 플러그인)가 커밋 뒤 네트워크 문제로 못 끝나는 일이 있다(897f944·3b99fbd). 로그 `~/.claude/security/log.txt`.
- 서브에이전트가 띄운 테스트 서버가 8080·5173을 잡고 있을 수 있다. 막히면 작업 관리자에서 끄거나 재부팅.
- 테스트(Gradle + Testcontainers) 도는 동안 CPU·메모리가 크게 오른다(정상). 편집기 Java 분석기(JDT)는 롬복을 못 알아봐서 "getId() 없음" 같은 가짜 빨간 줄을 띄운다 — `./gradlew test`가 기준.
- 서브에이전트 쓰기 제한 훅(scripts/allow-only.sh)은 jq가 필요하다. deploy 에이전트는 docs/deploy.md도 쓸 수 있다.
- Claude 앱이 대화마다 worktree를 만들 수 있다. 그 안의 커밋은 main에 합쳐야 IntelliJ(main)에 보인다.
- 포맷한 Windows에서 Docker가 안 켜지면: 관리자 터미널에서 `dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart` → `wsl --install --no-distribution` → 재부팅.
- 처음 git push할 때 Git Credential Manager 로그인 창이 뜬다(한 번 하면 저장).
- **EC2**: Amazon Linux, ssh 사용자는 **`ec2-user`**. 탄력적 IP가 없어 켤 때마다 퍼블릭 IP가 바뀐다(콘솔에서 확인). 다 쓰면 "중지"(종료 아님).
- **EC2 접속 키는 PC마다 따로**: 노트북·집 PC 각각 `~/.ssh/board-ec2`(별개 키). 보안그룹 SSH 규칙은 설명 `laptop`·`home-pc`. IP가 바뀌면 그 줄만 "내 IP"로 다시 저장. **집 인터넷 IP는 유동이라 자주 바뀐다**: ssh가 `Connection timed out`이면 먼저 이걸 의심(2026-11-09에도 바뀌어 있었음).
- **집 PC `board-ec2` 키에는 암호(passphrase)가 있어** Claude가 ssh·scp를 대신 못 한다. 서버 명령·scp는 사용자가 직접 치고 결과를 붙여 준다.
- 명령을 칠 때 **프롬프트로 PC·서버 구분**: `PS C:\...>`는 PC, `[ec2-user@...]$`는 서버. PC 창에서 `docker exec board-postgres ... psql`을 치면 로컬 DB에 들어간다. psql 나가기는 `\q`(역슬래시, 한글 키보드는 ₩), `board-#`로 바뀌면 문장이 안 끝난 상태 → Ctrl+C 후 `\q`.
- Windows PowerShell에서는 `C:\Windows\System32\OpenSSH\ssh.exe`(scp.exe도 같은 폴더) 전체 경로로. ssh 안에서 붙여넣기는 오른쪽 클릭. 프롬프트가 서버인지 PC인지 확인.
- 배포 방법: EC2 켜기 → Actions에서 main **CI가 images까지 초록** 확인 → Deploy → Run workflow. EC2가 꺼져 있으면 Deploy가 "EC2 상태·IP 확인"에서 멈춤. CI·Deploy 상태는 GitHub 공개 API로 메인 대화가 확인 가능.
- Deploy가 배포 직전에 DB를 자동 백업한다(~/board/backups). 실패·되돌리기는 docs/deploy.md "Flyway(표 변경) 문제와 되돌리기".
- docker-compose.yml을 바꾸면 Deploy만으로는 반영 안 됨. 먼저 scp로 서버에 복사. 서버 compose의 image 줄을 옛 SHA로 바꾸면 다음 Deploy도 그 SHA를 받으니 원복 필수.
- 헬스체크에 Redis가 포함돼 Redis가 죽으면 503 → Deploy 실패(의도). 메일 헬스체크는 꺼 둠(Gmail 장애로 배포 안 막힘).
- gh CLI가 없다. CI·Actions 결과는 브라우저 또는 GitHub 공개 API로.
- main에 push할 때마다 Docker Hub에 커밋 SHA 태그가 쌓인다(되돌리기에 쓴다).
- Windows에서 새 실행 스크립트를 커밋하면 실행 권한이 빠진다. 리눅스 러너용이면 `git update-index --chmod=+x <파일>`.
- Boot 4는 Jackson 3라 import가 `tools.jackson.databind`. postgresql 드라이버는 runtimeOnly라 `PSQLException`을 main 코드에서 못 쓴다.
- Docker Hub 429면 AWS 미러: `docker pull public.ecr.aws/docker/library/redis:8.10.3-alpine` → `docker tag ... redis:8.10.3-alpine`(postgres도 같은 방식).
- 사용자 선호: 입문자라 새 도구는 비유 한 줄씩. 백엔드에 관심. 정리는 **실제 코드 5~20줄 + 한 줄 해설**(채팅·Notion), 흐름도는 ASCII 코드 블록. 보고는 짧게. **도구 쓰기 전 설명 문장까지 전부 한국어**(영어 섞으면 지적받음). **명령어를 치라고 할 때는 왜 치는지 + 조각별 뜻을 같이**(2026-11-09 요청). S번호가 끝나면 백엔드 로직 정리 + 전체 코드 리뷰를 같이 보여주고 Notion 카드에도 남긴다. **프론트만 바꾸는 단계는 Notion에 쓰지 않는다.** 화면 작업은 frontend-design 스킬 + docs/design.md.
- **build 전에 "이번 단계에서 무엇을 만드는지" 쉬운 말로 설명하고 OK를 받는다**(큰 계획 OK는 단계 build 승인이 아님. 2026-11-11 지적받음).
- **테스트가 진짜로 버그를 잡는지 확인하는 법(S13에서 3번 씀):** 고친 줄을 잠깐 빼고 그 테스트만 돌려 실패하는지 본다. 원본은 임시 폴더에 복사 → 복사본으로 되돌리고 `cmp`로 같은지 확인.
- 로컬 백엔드 띄우기: `.claude/launch.json`의 backend는 `.env`를 환경변수로 넣고 `gradlew.bat bootRun`을 부르는 ps1을 가리킨다. 그 ps1은 세션 임시 폴더에 있어서 새 세션에선 없음 → 다시 만들기. **PowerShell 5.1 스크립트 안에 한글 경로를 쓰면 깨진다**(경로는 `(Get-Location).Path`로, .env는 `[IO.File]::ReadAllLines(..., UTF8)`로 읽기).
- 이 PC 로컬 DB: V9까지 적용. 연습 계정 `market_seller1`(고려대 인증, 중고 글 1개 DONE). 비밀번호는 세션 임시 폴더에만 있음. 새 계정 인증은 `MAIL_MODE=log`로 띄우고 서버 로그의 `[가짜 메일 발송] ... code=`를 읽는다.
- 중고 글 수정·상태 변경은 **조건부 UPDATE**만 쓴다. 엔티티 값을 바꿔 저장(dirty checking)하면 모든 칸을 UPDATE해서 상태가 옛 값으로 돌아간다(S13-b 리뷰에서 발견). 비자 후기 수정에는 아직 "수정 중 삭제 → 500" 빈틈이 남아 있음.
