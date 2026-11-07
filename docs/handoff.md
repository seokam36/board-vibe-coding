# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-07 (집 PC)

## 지금 어디까지
- **S0~S7 완료, EC2에 S7까지 배포됨.** 백엔드 테스트 299개 통과.
- 사이트 컨셉 확정: **같은 학교 유학생 선배들의 경험이 쌓이는 외국인 유학생 커뮤니티.** 로드맵과 결정은 docs/backlog.md의 S7~S13. 자취·알바 후기는 S13 이후 검토.
- S7(학교별 게시판과 말머리): V2(학교 5곳, 글에 학교·말머리), 글 API, 목록 거르기, 목록·상세·글쓰기·수정 화면, 배포. 운영 기존 글 5개는 학교 미지정·잡담으로 유지.
- Notion: S7 카드, 단계 기록, 백엔드 로직·코드 리뷰, 상위 페이지 진행 현황 갱신 완료.
- 작업 방식: 배포는 큰 기능(S번호) 끝날 때 한 번만. planner 결과·review [필수 수정]·실패 2회 초과·커밋/push/배포 전에만 멈춘다.

## 다음에 할 일
- **S8 댓글과 공감 시작** → planner. 첫 결정으로 댓글 있는 글의 삭제 방식을 정한다(안 정하면 FK 위반으로 글 삭제가 500이 될 수 있다). 표 변경은 V3.
- 노트북에서 처음 bootRun하기 전에 아래 "로컬 DB(노트북)" 정리부터.

## 진행 중인 단계
- 없음. 커밋 안 된 변경: `.claude/launch.json`(집 PC 전용 실행 설정, 일부러 커밋 안 함)
- 미룬 작은 정리: 글쓰기에서 학교·말머리 목록을 받는 중 저장하면 "골라 주세요"가 뜨는 문구, 학교·말머리 목록을 불러오는 중복 코드 훅으로 묶기, PostService의 FK 위반 401이 학교 FK도 포함하는 점(학교 삭제 기능이 생길 때 수정), 프론트 파일 줄바꿈 규칙(`.gitattributes`).
- 선택 정리(급하지 않음): EC2 콘솔 키 페어의 옛 `demo-key` 삭제, IAM 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- **표(테이블)를 바꾸는 기능은 새 V 파일**(다음은 `src/main/resources/db/migration/V3__...sql`)로. 커밋된 V 파일은 고치지 않는다. ddl-auto는 validate 유지(CLAUDE.md 규칙).
- S7 전까지 쓰던 옛 이미지로 되돌려도 앱과 글쓰기는 된다(임시 DB로 시험, 운영에서는 미시험). 자세한 건 docs/deploy.md (b). V3 이상은 다시 확인해야 한다.
- 집 PC PowerShell은 `npm.ps1`을 막는다. `.claude/launch.json`의 frontend는 `npm.cmd`를 쓴다.
- preview 도구가 프론트 포트를 5174라고 안내해도 Vite가 실제로 5173에 뜬 적이 있다. 접속이 안 되면 5173을 먼저 본다.
- Windows Git Bash의 `curl -d`에 한글 JSON을 넘기면 인코딩이 깨져 400이 날 수 있다.
- PostgreSQL 컨테이너의 정렬이 한글 가나다순과 달라, 학교 이름 순서는 서버 코드에서 정렬한다.
- **로컬 DB(노트북)**: 노트북 로컬 DB에는 Flyway 기록장이 없어서 지금 코드로 bootRun하면 `Found non-empty schema(s) ... but no schema history table`로 앱이 안 뜬다. 로컬 연습 데이터는 버려도 되니 비우고 시작한다: `docker exec board-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"'` → bootRun하면 V1이 표를 만든다. (집 PC 로컬 DB는 이미 기록장이 있어 그대로 된다)
- `.env`는 git에 없다. `.env.example`을 복사해 만들고 값을 채운다. 필요한 키: DB_*, POSTGRES_*, JWT_SECRET, REDIS_PASSWORD.
- 로컬 `docker compose up -d`는 postgres + redis 둘 다 띄운다. Docker Desktop이 꺼져 있으면 먼저 켠다(daemon 연결 에러).
- `./gradlew bootRun`은 `.env`를 자동으로 읽지 않는다. PowerShell에서 환경변수로 넣고 실행한다(집 PC는 `.claude/launch.json`의 backend 설정이 이걸 해 준다).
- 프론트 로컬 실행: `frontend`에서 `npm install` 후 `npm run dev` → http://localhost:5173. 브라우저는 반드시 5173(Vite 프록시 `/api`)으로.
- 프론트에는 자동 테스트 도구가 없다(lint·build + 브라우저 수동 확인).
- 서브에이전트가 띄운 테스트 서버가 8080·5173을 잡고 있을 수 있다. 메인은 끌 권한이 없다. 막히면 작업 관리자에서 끄거나 재부팅.
- **집 PC의 `python`은 Windows 스토어 가짜 바로가기**라 아무것도 실행하지 않는다(오류도 안 냄). 파일 수정은 Edit 도구로, 커밋 전 `git diff --stat`으로 확인.
- 알려진 한계(backlog): 여러 탭 동시 재발급 시 모두 로그아웃될 수 있음. 403은 CSRF 실패와 "본인 글 아님"을 화면에서 구분 못 함.
- 서브에이전트 쓰기 제한 훅(scripts/allow-only.sh)은 jq가 필요하다. deploy 에이전트는 이제 docs/deploy.md도 쓸 수 있다.
- Claude 앱이 대화마다 worktree를 만들 수 있다. 그 안의 커밋은 main에 합쳐야 IntelliJ(main)에 보인다.
- 포맷한 Windows에서 Docker가 안 켜지면: 관리자 터미널에서 `dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart` → `wsl --install --no-distribution` → 재부팅.
- 처음 git push할 때 Git Credential Manager 로그인 창이 뜬다(사람이 직접 한 번). 한 번 하면 저장된다.
- **EC2**: Amazon Linux, ssh 사용자는 **`ec2-user`**(ubuntu 아님). 탄력적 IP가 없어 켤 때마다 퍼블릭 IP가 바뀐다(콘솔에서 확인). 다 쓰면 "중지"(종료 아님).
- **EC2 접속 키는 PC마다 따로**: 노트북 `~/.ssh/board-ec2`, 집 PC `~/.ssh/board-ec2`(별개 키, 비밀번호 걸림). 보안그룹 SSH 규칙은 설명 `laptop`·`home-pc` 두 줄. 집·노트북 IP가 바뀌면 그 줄만 "내 IP"로 다시 저장.
- Windows PowerShell에서는 `C:\Windows\System32\OpenSSH\ssh.exe`(scp.exe도 같은 폴더) 전체 경로로 쓴다. ssh 안에서 붙여넣기는 **오른쪽 클릭**. 명령 전에 프롬프트가 서버(`ec2-user@...`)인지 PC(`PS ...`)인지 확인.
- 배포 방법: EC2 켜기 → Actions에서 main **CI가 images까지 초록**인지 확인(images가 끝나야 새 latest가 올라감) → Deploy → Run workflow. EC2가 꺼져 있으면 Deploy가 "EC2 상태·IP 확인"에서 바로 멈춘다(서버 영향 없음).
- Deploy가 배포 직전에 DB를 자동 백업한다(~/board/backups). 실패·되돌리기·옛 백업 복구(일회성 baseline)는 docs/deploy.md "Flyway(표 변경) 문제와 되돌리기".
- docker-compose.yml을 바꾸면 Deploy만으로는 EC2에 반영되지 않는다. 먼저 scp로 서버에 복사(docs/deploy.md). 반대로 서버 compose의 image 줄을 옛 SHA로 바꾸면 다음 Deploy도 그 SHA를 받으니 원복 필수.
- 헬스체크에 Redis가 포함돼 Redis가 죽으면 503 → Deploy가 실패한다(의도한 동작).
- gh CLI가 없다. CI·Actions 결과는 브라우저 또는 GitHub 공개 API(curl)로 확인.
- main에 push할 때마다 Docker Hub에 커밋 SHA 태그가 하나씩 쌓인다(되돌리기에 쓴다).
- Windows에서 새 실행 스크립트를 커밋하면 실행 권한이 빠진다. 리눅스 러너용이면 `git update-index --chmod=+x <파일>`.
- Boot 4는 Jackson 3라 import가 `tools.jackson.databind`다.
- Docker Hub 429(Too Many Requests)면 AWS 미러에서 받아 이름을 붙인다: `docker pull public.ecr.aws/docker/library/redis:8.10.3-alpine` → `docker tag ... redis:8.10.3-alpine`(postgres도 같은 방식).
- IDE가 롬복 때문에 띄우는 빨간 줄은 가짜 경고다. `./gradlew test`가 기준.
- 사용자 선호: 입문자라 새 도구는 비유 한 줄씩 몇 개만. 백엔드에 관심. 흐름·로직 설명은 그림(채팅 다이어그램, Notion mermaid)과 같이. 백엔드 아닌 단계의 Notion 기록은 워크플로 흐름 위주. 보고는 짧게. S번호가 끝나면 그 파트의 백엔드 로직 정리와 전체 코드 리뷰 결과를 같이 보여주고 Notion 카드에도 남긴다.
