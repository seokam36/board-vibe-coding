# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-05 (노트북)

## 지금 어디까지
- **S0~S4 완료, EC2에 S4까지 배포됨.**
- **S5(닉네임 + 회원정보 수정 + 탈퇴) 백엔드 끝(S5-a~d 커밋).** 백엔드 테스트 266개 통과. 화면(S5-e)과 배포(S5-f)가 남았다.
  - S5-a 닉네임(가입 필수, 2~20자 한글·영문·숫자·밑줄, 중복 409, 글 작성자는 {id, nickname}, /auth/me가 DB 확인)
  - S5-b 회원별 리프레시 토큰 목록(Redis Set `user-refresh:{id}`) + revokeAll
  - S5-c PUT /users/me/nickname, PUT /users/me/password(현재 비번 틀리면 400, 성공 시 204 + 쿠키 삭제 + 모든 기기 로그아웃)
  - S5-d DELETE /users/me {password}(완전 삭제, 글은 author null, 회원 행 FOR UPDATE 잠금, 같은 아이디 재가입 가능)
- Notion: S5 카드("닉네임 달고 내 정보 고치기") 안에 S5-a~d 기록 완료, 상위 페이지 진행 현황 갱신.
- 작업 방식: 배포는 큰 기능(S번호) 끝날 때 한 번만. planner 결과·review [필수 수정]·실패 2회 초과·커밋/push/배포 전에만 멈춘다.

## 다음에 할 일
- **S5-e 화면 build 시작** (e-1 가입 닉네임 칸·닉네임 표시 / e-2 내 정보 페이지: 닉네임·비밀번호 변경, 탈퇴). 화면을 브라우저로 확인하기 전에 **로컬 DB의 기존 회원을 지워야 한다**(아래 주의할 점).

## 진행 중인 단계
- S5: a~d 끝, e·f 남음. 커밋 안 된 변경 없음.
- S5에서 정한 것(planner 승인): 아이디 변경 불가, 닉네임 변경엔 비밀번호 확인 없음, 가입 409 문구는 "아이디 또는 닉네임이 이미 사용 중" 하나, 비밀번호 변경·탈퇴 성공 시 로그아웃 상태로 화면 이동, 탈퇴 전 확인 창.
- S5-f 배포 순서(확정): DB 백업(서버+PC) → 서버 DB에서 기존 회원 글의 author_id를 null로 → 기존 회원 삭제 → Deploy. 회원 수를 먼저 확인하고, SQL은 메인이 준비해서 사람이 ssh로 실행.
- 선택 정리(급하지 않음): EC2 콘솔 → 키 페어에서 옛 `demo-key` 삭제, IAM 사용자의 옛 키 태그 삭제, 노트북 `~/.ssh/deploy-actions` 개인키 삭제(이미 GitHub Secret에 들어감).
- S4-f에서 생략한 확인(나중에 해도 됨): 서버 화면에서 가입·로그인·글쓰기·남의 글 버튼 숨김, IAM 새 키 "마지막 사용", GitHub Sessions·SSH keys 점검.

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- `.env`는 git에 없다. `.env.example`을 복사해 만들고 값을 채운다. 필요한 키: DB_*, POSTGRES_*, JWT_SECRET, REDIS_PASSWORD. 없으면 `docker compose up`이 바로 실패한다.
- 로컬 `docker compose up -d`는 postgres + redis 둘 다 띄운다.
- `./gradlew bootRun`은 `.env`를 자동으로 읽지 않는다. PowerShell에서 DB_*, JWT_SECRET, REDIS_PASSWORD를 환경변수로 넣고 실행한다.
- 프론트 로컬 실행: `frontend`에서 `npm install` 후 `npm run dev` → http://localhost:5173. 브라우저는 반드시 5173(Vite 프록시 `/api`)으로 쓴다. 8080에 직접 붙으면 쿠키·CSRF가 안 된다.
- 프론트에는 자동 테스트 도구가 없다(lint·build + 브라우저 수동 확인). 쿠키를 지워야 하는 확인(F12 → Application → Cookies)은 사람이 해야 한다.
- 서브에이전트가 띄운 테스트 서버(java/node)가 안 꺼지고 8080·5173을 잡고 있을 수 있다. 메인은 그 프로세스를 끌 권한이 없다. 포트가 막히면 사람이 작업 관리자에서 끄거나 재부팅.
- 알려진 한계(backlog에 기록): 여러 탭이 동시에 재발급하면 모두 로그아웃될 수 있다. 403은 CSRF 실패와 "본인 글 아님"을 화면에서 구분 못 한다(백엔드 에러 코드 추가 후보).
- 서브에이전트 쓰기 제한 훅(scripts/allow-only.sh)은 jq가 필요하다. 없으면 `winget install jqlang.jq`, `jq --version`이 안 되면 jq.exe를 PATH 폴더에 복사.
- Claude 앱이 대화마다 worktree(`.claude/worktrees/<이름>`, 브랜치 `claude/<이름>`)를 만들 수 있다. 그 안의 커밋은 main에 합쳐야 IntelliJ(main)에 보인다.
- Docker Desktop: 일시정지 버튼을 누르면 compose가 "manually paused"로 실패(재시작으로 해결). daemon 연결 에러면 Docker Desktop을 켠다.
- 포맷한 Windows에서 Docker가 안 켜지면: 관리자 터미널에서 `dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart` → `wsl --install --no-distribution`(손상 안내가 뜨면 아무 키로 복구) → 재부팅.
- 이 PC에서 처음 git push할 때 Git Credential Manager 로그인 창이 떠야 한다(사람이 직접 터미널에서 push해서 로그인). 한 번 하면 저장된다.
- **EC2**: 탄력적 IP가 없어 켤 때마다 퍼블릭 IP가 바뀐다. 다 쓰면 "중지"(종료 아님). 배포·접속 중에는 중지하지 말 것.
- **EC2 접속 키는 PC마다 따로**: 노트북은 `~/.ssh/board-ec2`. 집 PC에서 서버에 ssh하려면 집 PC에서 새 키를 만들어 docs/deploy.md의 Instance Connect 절차로 `authorized_keys`에 추가한다. 보안그룹 SSH는 "내 IP"라 와이파이가 바뀌면 규칙도 바꿔야 한다.
- 이 노트북의 Windows PowerShell은 `ssh`를 못 찾는다. `C:\Windows\System32\OpenSSH\ssh.exe`(scp.exe, ssh-keygen.exe도 같은 폴더) 전체 경로로 쓴다. ssh 안에서 붙여넣기는 Ctrl+V가 아니라 **오른쪽 클릭**. 명령 전에 프롬프트가 서버(`ubuntu@...`)인지 로컬(`PS ...`)인지 확인.
- 배포 방법: EC2 켜기 → Actions에서 main CI 초록 확인 → Actions → Deploy → Run workflow. DB 구조가 바뀌는 배포 전에는 docs/deploy.md "배포 전 DB 백업"(서버 + PC `~/board-backups/`). 손 배포는 docs/deploy.md의 "비상용(수동)".
- docker-compose.yml을 바꾸면 Deploy 버튼만으로는 EC2에 반영되지 않는다. 먼저 scp로 서버에 복사(docs/deploy.md).
- 헬스체크에 Redis가 포함돼 Redis가 죽으면 503 → Deploy가 실패한다(의도한 동작).
- GitHub Secrets(DOCKERHUB_USERNAME/TOKEN, AWS_ACCESS_KEY_ID/SECRET_ACCESS_KEY, EC2_SSH_KEY)와 Variables(AWS_REGION, EC2_INSTANCE_ID, EC2_SECURITY_GROUP_ID)는 2026-11-05 새 값으로 교체 완료.
- gh CLI가 설치돼 있지 않다. CI·Actions 결과는 브라우저에서 확인한다.
- main에 push할 때마다 Docker Hub에 커밋 SHA 태그가 하나씩 쌓인다.
- Windows에서 새 실행 스크립트를 커밋하면 실행 권한이 빠진다. 리눅스 러너에서 쓸 파일이면 `git update-index --chmod=+x <파일>`.
- Boot 4는 Jackson 3라 import가 `tools.jackson.databind`다(`com.fasterxml` 아님).
- **로컬 DB 회원 정리(S5)**: users에 닉네임 NOT NULL 칸이 생겨서, 기존 회원이 있는 로컬 DB로 `bootRun`하면 칸 추가가 경고만 남기고 실패해 가입이 깨진다. 띄우기 전에 로컬 postgres에서 `update posts set author_id = null;` → `delete from users;` 실행(Redis는 그대로 둬도 됨). 테스트(Testcontainers)는 빈 DB라 상관없다.
- **Docker Hub 429**: 로그인 안 한 상태로 이미지를 많이 받으면 "Too Many Requests"로 막힌다(테스트의 Redis 컨테이너가 못 뜸). AWS 공식 미러에서 받아 이름을 붙이면 된다: `docker pull public.ecr.aws/docker/library/redis:8.10.3-alpine` → `docker tag public.ecr.aws/docker/library/redis:8.10.3-alpine redis:8.10.3-alpine`(postgres도 같은 방식). 다른 PC에서 처음 테스트할 때 겪을 수 있다.
- IDE(VS Code 자바 확장)가 `getId() is undefined` 같은 빨간 줄을 띄우는 건 롬복을 못 읽어서 나는 가짜 경고다. `./gradlew test`가 기준.
- 사용자 선호: 입문자라 새 도구·클래스는 비유 한 줄씩 몇 개만 소개. 백엔드에 관심이 많다. 보고는 짧게.
