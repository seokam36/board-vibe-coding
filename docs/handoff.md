# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-08 (집 PC)

## 지금 어디까지
- **S0~S9 + UI1 완료, EC2에 전부 배포됨.** 백엔드 테스트 489개 통과.
- 사이트 컨셉: **같은 학교 유학생 선배들의 경험이 쌓이는 외국인 유학생 커뮤니티.** 로드맵과 결정은 docs/backlog.md.
- S9(신고와 관리자): V4 users.role(USER/ADMIN), 토큰 role 클레임, `auth/service/AccessGuard`(requireUser / checkOwnerOrAdmin / requireAdmin — 토큰 ADMIN이면 DB 재확인), 글·댓글 삭제 "본인 또는 관리자"(수정은 본인만), V5 reports 표 + 신고 API, `/admin/**`(hasRole + DB 재확인) 신고 목록(left join fetch로 쿼리 고정)·기각(조건부 UPDATE), 화면. 운영 관리자 1명 지정됨(SQL, docs/deploy.md "관리자 지정·해제").
- S9 후속(42bf050): 글·댓글 삭제를 JPQL 한 문장+행 수로(동시 삭제 늦은 쪽 404), `UserRole.fromClaim` 하나로 role 판단, JSON 숫자 enum 400.
- UI1(화면 꾸미기, 프론트만): 공용화(describeError·Notice·isAdmin·Loading), **항상 어두운 화면**(청자 계열 녹회색 바탕 + 청자색 포인트, IBM Plex Sans KR), 공용 확인 창(`useConfirm` + `<dialog>`). 디자인 기준은 **docs/design.md** — 화면 고칠 때 이 파일의 색·크기만 쓴다(hex는 index.css `:root`에만).
- Notion: S9 카드(번호 10) — S9-a~e 단계 기록, S9 백엔드 로직 정리·코드 리뷰(+반영 결과), 상위 진행 현황 S9 체크. **UI1은 Notion에 안 씀**(사용자 요청: 프론트 단계는 Notion 정리 안 함).
- 작업 방식: 배포는 큰 기능 끝날 때 한 번만. planner 결과·review [필수 수정]·실패 2회 초과·커밋/push/배포 전에만 멈춘다. 정리(채팅·Notion)는 실제 코드 조각 + 해설 표, 흐름도는 ASCII 코드 블록.

## 다음에 할 일
- **S10 학교 메일 인증 시작** → backlog 항목 확인 후 planner. S9 리뷰 메모(Notion "S9 백엔드 로직 정리와 코드 리뷰" 끝): "인증 완료"는 role에 넣지 말고 별도 칸(또는 표, V6)으로 — 인증된 관리자도 있을 수 있음. 검사는 `AccessGuard.requireVerified`처럼 토큰 → DB 재확인 패턴. 인증 직후 새 토큰을 바로 발급해 15분 묵힘 회피. 메일 코드는 리프레시 토큰처럼 Redis에 짧게. "글쓰기는 인증 후"는 서비스에서 검사(Security 경로 규칙 아님).
- 노트북에서 처음 bootRun하기 전에 아래 "로컬 DB(노트북)" 정리부터.

## 진행 중인 단계
- 없음. 커밋 안 된 변경: `.claude/launch.json`(집 PC 전용 실행 설정, 일부러 커밋 안 함)
- 미룬 것(S9 리뷰 "나중"): 관리자 목록 페이징 · 기각된 신고는 같은 사람이 재신고 불가(UNIQUE가 상태 무관 — 의도면 api.md 한 줄) · 관리자 삭제로 처리하면 신고 이력 없음(S11 통계 때 재검토) · 서버 에러 message가 브라우저에 안 감(include-message never, 화면은 상태코드로 문구) · SecurityConfig `BearerTokenResolver` deprecated 경고 · JPQL enum 전체 경로 리터럴 · `ReportResponse.ReasonInfo` 중복 DTO · 유령 토큰의 글·댓글 수정은 401이 아니라 403.
- 미룬 것(UI1 리뷰): 글쓰기·공감·신고의 CSRF 403 안내 문구 없음(기본 문구로 떨어짐) · `describeError` 프론트 단위 테스트 없음(프론트 테스트 도구 자체 없음) · /admin/reports 비로그인 시 /login 대신 안내만 · Google Fonts 대신 셀프 호스팅(개인정보).
- 미룬 것(S8 이전): 글 목록 페이징 + 목록 응답에서 본문 빼기 · 댓글 수정 중 글이 CASCADE로 사라지면 500 · `Location: /comments/{id}`인데 단건 GET 없음 · 학교·말머리 목록 중복 코드 훅으로 · `.gitattributes` 줄바꿈 규칙.
- 선택 정리(급하지 않음): EC2 콘솔 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `src/main/resources/db/migration/V6__...sql`)로. 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate 유지(CLAUDE.md 규칙).
- **FK 제약에는 반드시 이름을 지어라**(`CONSTRAINT fk_xxx_yyy FOREIGN KEY ...`). 서비스가 제약 이름으로 404/401을 구분한다. V1의 `posts.author_id` FK는 이름을 안 지어 해시 이름이라 글쓰기만 "23503이면 401"로 둔다.
- **DB 오류 메시지 언어는 영어(기본값) 유지.** `ConstraintViolations`가 메시지의 `constraint "..."`에서 제약 이름을 꺼낸다. `lc_messages`를 바꾸면 경쟁 상황에서 500(docs/deploy.md).
- 경쟁 조건 테스트는 `LockRaceSupport.잠그고_요청하고_삭제커밋(...)` 사용(잠금 → 다른 스레드 요청 → pg_stat_activity로 락 대기 확인 → 커밋 → 결과). 유령 토큰은 `TestLogin.행없는_회원_토큰(jwtEncoder)`.
- 옛 이미지로 되돌리기: V3·V4·V5는 칸 추가(DEFAULT)·새 표만이라 되돌려도 뜬다(docs/deploy.md (b)). V2 되돌리기 주의도 같은 곳. V6부터는 다시 확인.
- 관리자는 API가 없고 운영 DB에서 SQL로만 지정(docs/deploy.md "관리자 지정·해제", 명령의 `<아이디>`를 실제 아이디로 바꿀 것). 지정 후 로그아웃 → 다시 로그인해야 토큰에 반영(해제는 즉시).
- **Jackson 3 설정 이름 함정**: 숫자 enum 막기는 `spring.jackson.datatype.enum.fail-on-numbers-for-enums`. 옛 이름(`spring.jackson.deserialization.fail-on-numbers-for-enums`)을 쓰면 앱이 아예 안 뜬다.
- 집 PC PowerShell은 `npm.ps1`을 막는다. `.claude/launch.json`의 frontend는 `npm.cmd`를 쓴다. preview 도구로 backend·frontend 둘 다 띄울 수 있다(backend는 .env를 읽어 bootRun).
- 집 PC 로컬 DB에는 V5까지 적용됐고 테스트 계정·글이 있다(연습용, 버려도 됨). 로컬 테스트 계정 `s9admin`은 role=ADMIN, `s9writer`는 USER(비밀번호는 연습용, 운영과 무관).
- 집 PC에서 다른 프로젝트(oncare)가 8080·5173을 쓰고 있을 수 있다. 그러면 preview가 다른 포트로 뜨거나 bootRun이 "Port 8080 was already in use"로 실패한다. 사용자에게 물어서 그쪽을 끄거나, 임시 설정으로 다른 포트(백엔드 `SERVER_PORT`, Vite `--config`로 프록시 대상 변경)를 쓴다.
- **Vite가 수정된 파일을 못 읽고 옛 내용을 줄 때가 있다**(서브에이전트가 파일을 고친 직후). 화면 동작이 코드와 다르면 먼저 frontend 서버를 껐다 켠다.
- 백엔드를 끈 채 화면을 열면 Vite 프록시가 502를 줘서 "서버에 연결할 수 없습니다"가 아니라 각 화면의 기본 실패 문구가 뜬다(정상).
- Windows Git Bash의 `curl -d`에 한글 JSON을 넘기면 인코딩이 깨져 400이 날 수 있다.
- PostgreSQL 컨테이너의 정렬이 한글 가나다순과 달라, 학교 이름 순서는 서버 코드에서 정렬한다.
- **로컬 DB(노트북)**: 노트북 로컬 DB에는 Flyway 기록장이 없어서 지금 코드로 bootRun하면 `Found non-empty schema(s) ... but no schema history table`로 앱이 안 뜬다. 로컬 연습 데이터는 버려도 되니 비우고 시작한다: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"'` → bootRun하면 V1~V3이 표를 만든다.
- `.env`는 git에 없다. `.env.example`을 복사해 만들고 값을 채운다. 필요한 키: DB_*, POSTGRES_*, JWT_SECRET, REDIS_PASSWORD.
- 로컬 `docker compose up -d`는 postgres + redis 둘 다 띄운다. Docker Desktop이 꺼져 있으면 먼저 켠다(daemon 연결 에러).
- `./gradlew bootRun`은 `.env`를 자동으로 읽지 않는다. PowerShell에서 환경변수로 넣고 실행한다(집 PC는 `.claude/launch.json`의 backend 설정이 이걸 해 준다).
- 프론트 로컬 실행: `frontend`에서 `npm install` 후 `npm run dev` → http://localhost:5173. 브라우저는 반드시 5173(Vite 프록시 `/api`)으로.
- 프론트에는 자동 테스트 도구가 없다(lint·build + 브라우저 확인. 메인 대화가 preview 도구로 직접 확인한다).
- 서브에이전트가 띄운 테스트 서버가 8080·5173을 잡고 있을 수 있다. 메인은 끌 권한이 없다. 막히면 작업 관리자에서 끄거나 재부팅.
- 집 PC의 `python`은 예전엔 스토어 가짜 바로가기였는데 2026-11-08엔 3.15.2가 실제로 실행됐다. 그래도 파일 수정은 Edit 도구로, 커밋 전 `git diff --stat`으로 확인.
- 알려진 한계(backlog): 여러 탭 동시 재발급 시 모두 로그아웃될 수 있음. 403은 CSRF 실패와 "본인 글 아님"을 화면에서 구분 못 함.
- 서브에이전트 쓰기 제한 훅(scripts/allow-only.sh)은 jq가 필요하다. deploy 에이전트는 docs/deploy.md도 쓸 수 있다.
- Claude 앱이 대화마다 worktree를 만들 수 있다. 그 안의 커밋은 main에 합쳐야 IntelliJ(main)에 보인다.
- 포맷한 Windows에서 Docker가 안 켜지면: 관리자 터미널에서 `dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart` → `wsl --install --no-distribution` → 재부팅.
- 처음 git push할 때 Git Credential Manager 로그인 창이 뜬다(사람이 직접 한 번). 한 번 하면 저장된다.
- **EC2**: Amazon Linux, ssh 사용자는 **`ec2-user`**(ubuntu 아님). 탄력적 IP가 없어 켤 때마다 퍼블릭 IP가 바뀐다(콘솔에서 확인). 다 쓰면 "중지"(종료 아님).
- **EC2 접속 키는 PC마다 따로**: 노트북 `~/.ssh/board-ec2`, 집 PC `~/.ssh/board-ec2`(별개 키, 비밀번호 걸림). 보안그룹 SSH 규칙은 설명 `laptop`·`home-pc` 두 줄. 집·노트북 IP가 바뀌면 그 줄만 "내 IP"로 다시 저장.
- Windows PowerShell에서는 `C:\Windows\System32\OpenSSH\ssh.exe`(scp.exe도 같은 폴더) 전체 경로로 쓴다. ssh 안에서 붙여넣기는 **오른쪽 클릭**. 명령 전에 프롬프트가 서버(`ec2-user@...`)인지 PC(`PS ...`)인지 확인.
- 배포 방법: EC2 켜기 → Actions에서 main **CI가 images까지 초록**인지 확인(images가 끝나야 새 latest가 올라감) → Deploy → Run workflow. EC2가 꺼져 있으면 Deploy가 "EC2 상태·IP 확인"에서 바로 멈춘다(서버 영향 없음). CI·Deploy 상태는 GitHub 공개 API(`Invoke-RestMethod .../actions/runs`)로 메인 대화가 확인할 수 있다.
- Deploy가 배포 직전에 DB를 자동 백업한다(~/board/backups). 실패·되돌리기·옛 백업 복구(일회성 baseline)는 docs/deploy.md "Flyway(표 변경) 문제와 되돌리기".
- docker-compose.yml을 바꾸면 Deploy만으로는 EC2에 반영되지 않는다. 먼저 scp로 서버에 복사(docs/deploy.md). 반대로 서버 compose의 image 줄을 옛 SHA로 바꾸면 다음 Deploy도 그 SHA를 받으니 원복 필수.
- 헬스체크에 Redis가 포함돼 Redis가 죽으면 503 → Deploy가 실패한다(의도한 동작).
- gh CLI가 없다. CI·Actions 결과는 브라우저 또는 GitHub 공개 API로 확인.
- main에 push할 때마다 Docker Hub에 커밋 SHA 태그가 하나씩 쌓인다(되돌리기에 쓴다).
- Windows에서 새 실행 스크립트를 커밋하면 실행 권한이 빠진다. 리눅스 러너용이면 `git update-index --chmod=+x <파일>`.
- Boot 4는 Jackson 3라 import가 `tools.jackson.databind`다. postgresql 드라이버는 runtimeOnly라 `PSQLException`을 main 코드에서 못 쓴다.
- Docker Hub 429(Too Many Requests)면 AWS 미러에서 받아 이름을 붙인다: `docker pull public.ecr.aws/docker/library/redis:8.10.3-alpine` → `docker tag ... redis:8.10.3-alpine`(postgres도 같은 방식).
- IDE가 롬복 때문에 띄우는 빨간 줄은 가짜 경고다. `./gradlew test`가 기준.
- 사용자 선호: 입문자라 새 도구는 비유 한 줄씩 몇 개만. 백엔드에 관심. 정리는 파일 이름 나열 말고 **실제 코드 5~20줄 + 한 줄 해설**(채팅·Notion 둘 다). 흐름도는 ASCII 코드 블록으로 짧게(mermaid TD 마름모는 너무 커서 지적받음). 보고는 짧게. S번호가 끝나면 그 파트의 백엔드 로직 정리와 전체 코드 리뷰 결과를 같이 보여주고 Notion 카드에도 남긴다. **프론트만 바꾸는 단계는 Notion에 쓰지 않는다**(backlog 한 줄 + 채팅 정리만). 화면 작업은 frontend-design 스킬 기준 + docs/design.md.
