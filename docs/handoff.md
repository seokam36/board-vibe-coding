# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-05 (노트북)

## 지금 어디까지
- **S0~S4 전부 완료.** S4(회원가입 + 로그인)는 S4-f(키 교체 + EC2 배포)까지 끝나 EC2에 올라가 있다. 백엔드 테스트 159개 통과, 프론트 lint·build 통과.
- 서버에서 확인한 것: 헬스 UP(DB+Redis), 글 수 유지, 옛 글 작성자 없음, 로그인 없이 POST는 403, 외부에서 8080·6379·5432 닫힘, access_token 쿠키를 지워도 자동 재발급으로 로그인 유지.
- Notion: S4-a~f 기록 완료(상위 페이지 진행 현황·Redis 아키텍처 그림 포함).
- 작업 방식: 배포는 큰 기능(S번호) 끝날 때 한 번만. planner 결과·review [필수 수정]·실패 2회 초과·커밋/push/배포 전에만 멈춘다.

## 다음에 할 일
- **다음 기능(S5) 정하기**: 사용자가 기능을 고르면 docs/backlog.md에 S5 항목 추가 → planner. 후보로 얘기만 나온 것: 비밀번호 변경·찾기, 회원 탈퇴, 아이디 중복 확인 버튼, backlog의 "여러 탭 동시 재발급" 한계.

## 진행 중인 단계
- 없음.
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
- 사용자 선호: 입문자라 새 도구·클래스는 비유 한 줄씩 몇 개만 소개. 백엔드에 관심이 많다. 보고는 짧게.
