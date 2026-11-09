# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-09 늦은 밤 (집 PC)

## 지금 어디까지
- **S0~S10 + UI1 완료·배포됨.**
- **S11 비자 후기와 학교별 통계: a~e 전부 커밋됨, 배포(S11-f)만 남음.** 백엔드 테스트 699개 통과. 운영 서버는 아직 S10 상태(V7 미적용).
  - S11-a V7(`visa_reviews` 표 + 인덱스) + `GET /visa-review-options`
  - S11-b `POST /visa-reviews`(인증 회원만, 학교 자동) + `GET /visa-reviews/{id}` + 탈퇴 시 후기 작성자 null
  - S11-c `GET /visa-reviews?…` 거르기 + `PUT`(본인만) + `DELETE`(본인·관리자)
  - S11-d `GET /visa-reviews/stats`(조합별 count·avgDays, 네이티브 group by 1번, 정렬은 자바에서) — push됨
  - S11-e-1 목록·상세 화면 / e-2 작성·수정·삭제 화면 / e-3 통계 화면(후기·통계 탭) — 로컬 커밋, push 전
  - fix: 글쓰기(PostCreatePage) 중 로그인이 풀리면 입력이 사라지던 버그(비자 후기 작성과 같은 lastSchool 방식)
- S11 결정(backlog S11 항목에 전부): 별개 표, 정해진 칸 + 선택 본문(2000자), 결과 난 후기만(승인·불허), 접수일 ≤ 결과일 ≤ 서울 오늘, 처리 일수는 계산(저장 X), 통계는 숨기지 않고 건수와 함께, 공식 링크는 하이코리아 하나(화면 상수), 읽기 누구나·쓰기 인증 회원, 학교는 작성 시점 인증 학교 복사, 탈퇴 후 재인증 허용(알려진 한계).
- 사이트 컨셉: **같은 학교 유학생 선배들의 경험이 쌓이는 외국인 유학생 커뮤니티.** 로드맵과 결정은 docs/backlog.md.
- S10 요약: 미인증은 읽기만, 글쓰기·댓글·공감·신고는 인증한 학교 글에만(`AccessGuard.requireVerified`/`requireSameSchool`, 매번 DB). 메일은 Gmail SMTP(`MAIL_MODE=smtp`), 원문 저장 없이 해시만. 자세한 건 backlog S10·docs/api.md.
- Notion: **S11 카드(번호 12)에 S11-a·b·c·d 기록 완료**(단계 S11-a~d), 진행 현황 S11 줄은 "남은 것: 화면·배포"(미체크). S11-e는 프론트 단계라 Notion에 안 씀.

## 다음에 할 일
- **S11-f 배포.** 먼저 `git push`(로컬 커밋 4개) → Actions CI가 images까지 초록 확인 → EC2 켜기 → Deploy. deploy가 docs/deploy.md에 "V7 후 S10 이미지로 되돌리기" 메모 추가(V7은 새 표·인덱스만이라 되돌려도 뜰 것 — 확인). 배포 후 운영에서 후기 작성·통계 확인(인증 회원 필요 → handoff "학교 메일 없이 운영에서 인증 테스트" 방법).
- 배포 끝나면: Notion S11-f 기록 + 진행 현황 S11 체크, **S11 파트 전체 백엔드 로직 정리 + 코드 리뷰**(사용자 선호)를 채팅과 Notion에.
- 사용자와 이야기한 후보(아직 backlog에 안 올림, 올릴지 물어보기): ① 프론트 기본 에러 문구표(화면마다 겹치는 404 등 줄이기) ② 프론트 자동 테스트(Vitest → Playwright, 매번 손으로 브라우저 확인하는 걸 줄이기).

## 진행 중인 단계
- S11(a~e 커밋, f 남음). 커밋 안 된 변경: `.claude/launch.json`(컴퓨터마다 다른 실행 설정, 일부러 커밋 안 함)
- 미룬 것(S11-e 리뷰): 목록·통계 화면의 요청·에러 문구 중복을 훅으로(세 번째 사용처 생길 때) · 탭이 주소 쿼리를 통째로 붙임(쿼리 늘면 필터 3개만) · PostCreatePage·VisaReviewCreatePage의 같은 6줄 → useVerifiedSchool 훅 · 상세에서 삭제 후 목록으로 갈 때 필터 사라짐(글도 같음) · "목록으로"도 필터 안 들고 감
- 미룬 것(S11 리뷰): 후기 수정 중 다른 쪽이 삭제하면 500(글 수정과 같은 한계) · 테스트의 `@MockitoBean Clock`이 컨텍스트를 따로 띄움(느려지면 공용 MutableClock) · RecordingSender를 공용 테스트 설정으로 · SecurityConfig `/visa-reviews/stats` 명시 matcher는 `{id}` 규칙과 중복(의도 표시용)
- 미룬 것(S10 리뷰 "나중"): 메일 해시가 소금 없는 SHA-256(Redis·DB 털리면 사전 역산 가능 → 나중에 HMAC로 둘 다) · Redis Cluster로 가면 인증 Lua 키(5개)가 한 슬롯에 안 묶임 · 인증 테스트 헬퍼(RecordingSender·sha256) 중복 · 409 "다른 계정이 쓴 메일"이 존재 여부를 조금 알려 줌 · Gmail 하루 약 500통 한도(근본 해결은 도메인 + AWS SES) · SMTP 장애 중 연타하면 요청마다 최대 ~25초 스레드 점유 · 운영 gmail.com 임시 도메인 테스트 뒤 그 시간대 인증 계정 수 확인 단계 · 인증 설정값 @Validated · api.md 400·410 문구는 실제 본문에 없음 · 운영 MAIL_MODE 빠지면 조용히 log 모드
- 미룬 것(S9 리뷰 "나중"): 관리자 목록 페이징 · 기각된 신고는 같은 사람이 재신고 불가 · 관리자 삭제로 처리하면 신고 이력 없음 · 서버 에러 message가 브라우저에 안 감(include-message never, 화면은 상태코드로 문구 — S10도 이 전제로 400/410 등으로 나눔) · SecurityConfig `BearerTokenResolver` deprecated 경고 · JPQL enum 전체 경로 리터럴 · `ReportResponse.ReasonInfo` 중복 DTO · 유령 토큰의 글·댓글 수정은 401이 아니라 403.
- 미룬 것(UI1 리뷰): 글쓰기·공감·신고의 CSRF 403 안내 문구가 학교 인증 403과 같은 문구로 섞임 · `describeError` 프론트 단위 테스트 없음 · /admin/reports 비로그인 시 /login 대신 안내만 · Google Fonts 대신 셀프 호스팅.
- 미룬 것(S8 이전): 글 목록 페이징 + 목록 응답에서 본문 빼기 · 댓글 수정 중 글이 CASCADE로 사라지면 500 · `Location: /comments/{id}`인데 단건 GET 없음 · 학교·말머리 목록 중복 코드 훅으로 · `.gitattributes` 줄바꿈 규칙(서브에이전트가 LF로 저장해 CRLF 경고가 계속 뜸, 내용엔 영향 없음).
- 선택 정리(급하지 않음): EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `V8__...sql`)로. 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate 유지.
- **FK·UNIQUE·CHECK 제약에는 반드시 이름**을 지어라. 서비스가 제약 이름으로 404/401/409를 구분한다(`ConstraintViolations`). V1의 `posts.author_id` FK만 해시 이름이라 글쓰기는 "23503이면 401".
- **DB 오류 메시지 언어는 영어(기본값) 유지.** `lc_messages`를 바꾸면 경쟁 상황에서 500(docs/deploy.md).
- 경쟁 조건 테스트는 `LockRaceSupport`, 유령 토큰은 `TestLogin.행없는_회원_토큰(jwtEncoder)`, 인증 회원은 `TestLogin.인증된_사용자(...)`·`학교_인증_처리(jdbc, username, 학교명)`. 글쓰기·댓글·공감·신고 테스트는 **인증 회원 + 같은 학교 글**이어야 한다.
- 테스트에서 메일 발송 결과를 보려면 `@Primary` 가짜 `VerificationMailSender`(SchoolEmailCodeApiTests의 RecordingSender 참고). 인증 Redis 키는 `email-verify*`(코드·cooldown·daily·pair·target), 테스트 전후 정리.
- 인증 관련 Redis 키 이름·막혔을 때 운영자가 푸는 법은 docs/deploy.md "학교 인증 코드가 막혔다는 문의가 오면".
- 로컬에서 실제 메일 확인하려면 `.env`에 MAIL_* + `MAIL_MODE=smtp`. 학교 도메인만 허용이라 개인 Gmail로 받으려면 **로컬 DB에만** `insert into school_email_domains (school_id, domain) select id,'gmail.com' from schools where name='연세대학교';` 후 테스트, 끝나면 delete. 화면 확인은 `MAIL_MODE=log`로 켜고 서버 로그의 `[가짜 메일 발송] ... code=`를 읽는다.
- 옛 이미지로 되돌리기: V3·V4·V5·V6은 칸 추가(NULL/DEFAULT)·새 표만이라 되돌려도 뜬다(docs/deploy.md (b)). V2 되돌리기 주의도 같은 곳.
- 관리자는 운영 DB SQL로만 지정(docs/deploy.md "관리자 지정·해제"). 지정 후 다시 로그인해야 토큰에 반영.
- **Jackson 3 설정 이름 함정**: 숫자 enum 막기는 `spring.jackson.datatype.enum.fail-on-numbers-for-enums`(옛 이름이면 앱이 안 뜸).
- 집 PC PowerShell은 `npm.ps1`을 막는다. `.claude/launch.json`의 frontend는 `npm.cmd`. 노트북 launch.json의 powershell은 전체 경로(`C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe`)여야 preview가 찾는다.
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173을 쓰고 있을 수 있다(물어보고 끄거나 다른 포트).
- 백엔드를 끈 채 화면을 열면 Vite 프록시 502 → 각 화면의 기본 실패 문구(정상).
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일로 만들어 `--data-binary @파일`로 보낸다. psql에 한글 SQL도 파일로(`docker cp` 후 `-f`).
- PostgreSQL 컨테이너의 정렬이 한글 가나다순과 달라 학교 이름 순서는 서버 코드에서 정렬.
- **학교 메일 없이 운영에서 인증 테스트**(S10-h 때 한 방법): [서버] psql에서 `INSERT INTO school_email_domains (school_id, domain) SELECT school_id, 'gmail.com' FROM school_email_domains WHERE domain = 'yonsei.ac.kr';` → 테스트 계정 새로 가입 → campuscode4 Gmail 주소로 인증(자기한테 보냄, 스팸함 확인) → 글쓰기 확인·삭제 → **`DELETE FROM school_email_domains WHERE domain = 'gmail.com';` 꼭** → 테스트 계정 탈퇴(회원 줄이 지워져 메일 해시도 사라짐). 그 사이엔 누구나 gmail로 연세대 인증 가능하니 짧게.
- 글쓰기 화면은 학교를 고르지 않고 인증한 학교로 고정(S10-f 설계). "다른 학교 글쓰기 막힘"은 화면에서 확인 불가, 테스트가 지킨다.
- 집 PC 로컬 DB: V7까지 적용됨. 연습 계정 `visa_me`(경희대 인증)·`visa_other`(고려대 인증)·`visa_new`(미인증)·`visa_admin`(관리자)과 연습 후기 몇 개가 있다(로컬 전용). 비밀번호는 세션 임시 폴더에만 적어 둬서 사라짐 → 필요하면 새로 가입 후 SQL로 `verified_school_id`·`role` 지정. 회원가입 API는 CSRF 필요(`GET /csrf` 쿠키 → `X-XSRF-TOKEN` 헤더).
- 브라우저 확인 요령: 로그인 풀림은 페이지 JS로 `/api/auth/logout`을 먼저 부르고 저장을 누르면 재현된다. 콘솔 에러는 탭에 계속 쌓이니 파일 버전(`?t=` 숫자)으로 옛 기록인지 구분.
- **비자 후기 "오늘"은 `Clock` 빈(ClockConfig, Asia/Seoul) 기준.** 서버는 UTC라 `LocalDate.now()`·`@PastOrPresent`를 쓰면 서울 밤에 오늘이 미래로 판정된다. 후기 테스트는 `@MockitoBean Clock`으로 서울 2026-11-09 12:00 고정(VisaReviewApiTests·VisaReviewListUpdateDeleteTests와 같은 설정을 써야 컨텍스트를 같이 씀).
- 후기 작성 테스트는 **인증 회원**이어야 한다(`TestLogin.인증된_사용자`·`학교_인증_처리`). 수정·삭제는 인증 불필요. 탈퇴는 `UserService.withdraw`가 posts·comments·visa_reviews 작성자를 비운 뒤 회원 삭제(작성자 FK에 ON DELETE 없음).
- SecurityConfig의 `GET /visa-reviews/{id}` 공개 규칙은 `/visa-reviews/` 아래 한 칸짜리 GET을 전부 공개로 만든다(`/stats`도 명시해 둠). 로그인 전용 GET을 그 아래 두려면 따로 막아야 한다.
- **Vite 옛 파일 문제가 이번에도 여러 번 났다**(서브에이전트가 고친 직후 화면이 하얗게 비고 `xxx is not defined`). build가 프론트를 고친 뒤에는 브라우저 확인 전에 frontend 서버를 항상 껐다 켠다.
- Flyway 테스트 `FlywayExistingBaselineTests`는 "새로 적용되는 V 파일 수"를 하드코딩한다 → V8을 만들면 같이 고쳐야 한다.
- 노트북 로컬 DB: 이제 기록장이 있고 V6까지 적용됨. 연습 계정 `mailtest01`(연세대 인증), `koreauser`(고려대 인증, 가짜 해시), `newbie`(미인증), 비밀번호는 연습용. 글 3개(연세대·고려대·학교 미지정).
- `.env`는 git에 없다. `.env.example`을 복사해 채운다. 필요한 키: DB_*, POSTGRES_*, JWT_SECRET, REDIS_PASSWORD, (선택) MAIL_*. **`.env`는 캡처해서 공유하지 말 것.**
- 로컬 `docker compose up -d`는 postgres + redis만(app은 `profiles: prod`). Docker Desktop이 꺼져 있으면 먼저 켠다.
- `./gradlew bootRun`은 `.env`를 자동으로 읽지 않는다. PowerShell에서 환경변수로 넣고 실행.
- 프론트 로컬 실행: `frontend`에서 `npm install` 후 `npm run dev` → http://localhost:5173(반드시 5173으로).
- 프론트에는 자동 테스트 도구가 없다(lint·build + 메인 대화가 preview 도구로 브라우저 확인).
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
