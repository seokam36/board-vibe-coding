# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-09 (집 PC)

## 지금 어디까지
- **S0~S10 + UI1 완료·배포됨.** S10-h 배포 끝(운영에서 실제 Gmail 발송·인증·글쓰기까지 확인). 백엔드 테스트 593개 통과.
- 운영 기존 회원 2명(관리자 포함)은 전부 미인증 → 학교 메일로 인증하기 전까지 글쓰기 불가(의도).
- 사이트 컨셉: **같은 학교 유학생 선배들의 경험이 쌓이는 외국인 유학생 커뮤니티.** 로드맵과 결정은 docs/backlog.md.
- S10 결정(backlog S10 항목에 전부): 가입 때 메일 안 받음, 내 정보에서 학교 메일 인증. 학교는 메일 도메인으로 자동 판별(V6 `school_email_domains`, 소문자만). 메일 원문은 DB에 없음(소문자 SHA-256만 `users.school_email_hash` UNIQUE). 인증 여부는 토큰 말고 매번 DB(`AccessGuard.requireVerified`/`requireSameSchool`). **미인증은 읽기만**, 글쓰기·댓글·공감·신고는 인증 후 **인증한 학교 글에만**(학교 미지정 옛 글은 읽기만, 관리자도 예외 없음). 수정·삭제·공감 취소는 그대로.
- 코드: 6자리, Redis 10분, 틀림 400 / 없음·만료·5회 틀림 폐기 410. 발송 제한(Redis Lua 한 번에): 계정 60초 → 계정 하루 10 → 같은 계정·같은 메일 하루 2 → 같은 메일 하루 5(계정 합산), 모두 429.
- 메일: Gmail SMTP(`MAIL_MODE=smtp` + MAIL_HOST/PORT/USERNAME/PASSWORD/FROM). 기본 log 모드(서버 로그에 코드). smtp인데 값 빠지면 앱 시작 실패. 발송 계정은 전용 Gmail(campuscode4), 앱 비밀번호 사용. 노트북에서 실제 메일 수신·인증까지 확인함(**첫 메일은 스팸함**).
- Notion: S10 카드(번호 11)에 S10-a~e, g 기록. S10-f는 프론트라 표 한 줄만. **S10-h 배포 기록·진행 현황 S10 체크는 아직 안 함.**

## 다음에 할 일
- **S10 마무리 3가지**(S9 때처럼):
  1. deploy 에이전트로 deploy.md에 "V6 후 옛 이미지(S9/UI1) 되돌리기" 메모(새 칸은 NULL 허용이라 뜨지만 인증 제한은 사라짐) + "학교 메일 없이 운영 인증 테스트하는 법"(아래 주의할 점) 추가
  2. Notion S10 카드에 S10-h 배포 기록 + 진행 현황 S10 체크
  3. S10 전체 백엔드 로직 정리 + 전체 코드 리뷰(review 에이전트) 보여 주고 Notion에도
- 그다음 새 기능 **S11 비자 후기와 학교별 통계**(에타와 다른 점이 여기서부터 생김) → backlog 항목 다듬고 planner.

## 진행 중인 단계
- 없음(S10-h 배포 끝). 커밋 안 된 변경: `.claude/launch.json`(컴퓨터마다 다른 실행 설정, 일부러 커밋 안 함)
- 미룬 것(S10 리뷰 "나중"): 메일 해시가 소금 없는 SHA-256(Redis·DB 털리면 사전 역산 가능 → 나중에 HMAC로 둘 다) · Redis Cluster로 가면 인증 Lua 키(5개)가 한 슬롯에 안 묶임 · 인증 테스트 헬퍼(RecordingSender·sha256) 중복 · 409 "다른 계정이 쓴 메일"이 존재 여부를 조금 알려 줌 · Gmail 하루 약 500통 한도(근본 해결은 도메인 + AWS SES) · SMTP 장애 중 연타하면 요청마다 최대 ~25초 스레드 점유
- 미룬 것(S9 리뷰 "나중"): 관리자 목록 페이징 · 기각된 신고는 같은 사람이 재신고 불가 · 관리자 삭제로 처리하면 신고 이력 없음 · 서버 에러 message가 브라우저에 안 감(include-message never, 화면은 상태코드로 문구 — S10도 이 전제로 400/410 등으로 나눔) · SecurityConfig `BearerTokenResolver` deprecated 경고 · JPQL enum 전체 경로 리터럴 · `ReportResponse.ReasonInfo` 중복 DTO · 유령 토큰의 글·댓글 수정은 401이 아니라 403.
- 미룬 것(UI1 리뷰): 글쓰기·공감·신고의 CSRF 403 안내 문구가 학교 인증 403과 같은 문구로 섞임 · `describeError` 프론트 단위 테스트 없음 · /admin/reports 비로그인 시 /login 대신 안내만 · Google Fonts 대신 셀프 호스팅.
- 미룬 것(S8 이전): 글 목록 페이징 + 목록 응답에서 본문 빼기 · 댓글 수정 중 글이 CASCADE로 사라지면 500 · `Location: /comments/{id}`인데 단건 GET 없음 · 학교·말머리 목록 중복 코드 훅으로 · `.gitattributes` 줄바꿈 규칙(서브에이전트가 LF로 저장해 CRLF 경고가 계속 뜸, 내용엔 영향 없음).
- 선택 정리(급하지 않음): EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `V7__...sql`)로. 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate 유지.
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
- **Vite가 수정된 파일을 못 읽고 옛 내용을 줄 때가 있다**(서브에이전트가 고친 직후). 화면 확인 전에 frontend 서버를 껐다 켠다.
- 백엔드를 끈 채 화면을 열면 Vite 프록시 502 → 각 화면의 기본 실패 문구(정상).
- **Windows Git Bash `curl -d`에 한글 JSON을 넣으면 400.** UTF-8 파일로 만들어 `--data-binary @파일`로 보낸다. psql에 한글 SQL도 파일로(`docker cp` 후 `-f`).
- PostgreSQL 컨테이너의 정렬이 한글 가나다순과 달라 학교 이름 순서는 서버 코드에서 정렬.
- **학교 메일 없이 운영에서 인증 테스트**(S10-h 때 한 방법): [서버] psql에서 `INSERT INTO school_email_domains (school_id, domain) SELECT school_id, 'gmail.com' FROM school_email_domains WHERE domain = 'yonsei.ac.kr';` → 테스트 계정 새로 가입 → campuscode4 Gmail 주소로 인증(자기한테 보냄, 스팸함 확인) → 글쓰기 확인·삭제 → **`DELETE FROM school_email_domains WHERE domain = 'gmail.com';` 꼭** → 테스트 계정 탈퇴(회원 줄이 지워져 메일 해시도 사라짐). 그 사이엔 누구나 gmail로 연세대 인증 가능하니 짧게.
- 글쓰기 화면은 학교를 고르지 않고 인증한 학교로 고정(S10-f 설계). "다른 학교 글쓰기 막힘"은 화면에서 확인 불가, 테스트가 지킨다.
- 집 PC 로컬 DB는 아직 V6 전(pull 후 앱을 안 켬). `./gradlew bootRun` 한 번 하면 V6 적용.
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
