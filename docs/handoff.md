# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-05 (집 PC, 다음 작업은 노트북)

## 지금 어디까지
- S0~S3 완료. S4(회원가입 + 로그인)는 S4-a~S4-e 완료, **S4-f(EC2 배포)만 남음**. 백엔드 테스트 159개 통과, 프론트 lint·build 통과.
- S4-e는 e-1(Vite 프록시·CSRF·가입·로그인·상단바), e-2(자동 재발급), e-3(작성자 표시·본인 글 버튼)으로 나눠 진행했다.
- Notion: S4-a~e 기록 완료. 기록 형식이 바뀌었다(docs/notion-guide.md: "⚙️ 서버 안에서 일어나는 일" 추가, 트러블슈팅·예상 질문 제외).
- 작업 방식: 배포는 큰 기능(S번호) 끝날 때 한 번만. planner 결과·review [필수 수정]·실패 2회 초과·커밋/push/배포 전에만 멈춘다.

## 다음에 할 일
- **S4-f 시작 (노트북)** — 키 교체(아래 1~3, 5) → EC2 DB 백업 → EC2 `.env`·compose 갱신(Redis, JWT_SECRET, REDIS_PASSWORD) → 배포 → 확인 → 키 마지막 사용 기록 점검(6). planner부터.

## 진행 중인 단계
- S4-f (아직 시작 안 함). 단계 목록은 docs/backlog.md.
- S4-f에서 할 것 (지금까지 정해진 것):
  - 키 교체를 배포 전에 끝낸다(아래 "키 교체" 참고). Docker Hub 토큰(4번)은 끝났다.
  - **배포 전에 EC2 DB 백업**(pg_dump). 운영 DB 구조가 처음 바뀌는 배포다(posts에 author_id 컬럼 추가, users 테이블 생성). 백업 단계를 docs/deploy.md에 넣는다.
  - EC2 `.env`에 `JWT_SECRET`, `REDIS_PASSWORD` 추가(새 값, 32바이트 이상 Base64 / 랜덤). DB 비밀번호도 새 값으로(5번).
  - docker-compose.yml이 S4-b·S4-c에서 바뀌었다(app에 JWT_SECRET·REDIS_*, redis 서비스 추가). Deploy 버튼만으로는 EC2에 반영 안 되니 먼저 scp로 복사(docs/deploy.md 비상용).
  - 정할 것: Redis가 `/actuator/health`에 포함돼 Redis가 죽으면 헬스체크가 503 → deploy.yml이 실패로 판단. 의도대로 둘지 S4-f에서 결정.
  - 배포 후 확인: 가입·로그인·글쓰기·남의 글 버튼 숨김, 옛 글은 "(작성자 없음)"으로 보이는지, 15분 뒤에도 로그인 유지.
  - 아키텍처가 바뀌니(Redis 추가) Notion 상위 페이지 mermaid 다이어그램도 고친다.
- 커밋 안 된 변경: `.claude/launch.json`(로컬 서버 실행 설정, 일부러 커밋 안 함)

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- `.env`는 git에 없다. `.env.example`을 복사해 만들고 값을 채운다. 필요한 키: DB_*, POSTGRES_*, JWT_SECRET, **REDIS_PASSWORD(새로 추가됨)**. 없으면 `docker compose up`이 바로 실패한다.
- 로컬 `docker compose up -d`는 이제 postgres + **redis** 둘 다 띄운다.
- `./gradlew bootRun`은 `.env`를 자동으로 읽지 않는다. PowerShell에서 DB_*, JWT_SECRET, REDIS_PASSWORD를 환경변수로 넣고 실행한다.
- 프론트 로컬 실행: `frontend`에서 `npm install` 후 `npm run dev` → http://localhost:5173. 브라우저는 반드시 5173(Vite 프록시 `/api`)으로 쓴다. 8080에 직접 붙으면 쿠키·CSRF가 안 된다.
- 프론트에는 자동 테스트 도구가 없다(lint·build + 브라우저 수동 확인). 쿠키를 지워야 하는 확인(F12 → Application → Cookies)은 사람이 해야 한다.
- 서브에이전트가 띄운 테스트 서버(java/node)가 안 꺼지고 8080·5173을 잡고 있을 수 있다. 메인은 그 프로세스를 끌 권한이 없다(자동 모드에서 거절됨). 포트가 막히면 사람이 작업 관리자에서 끄거나 재부팅.
- **S4-f 전까지 Deploy 버튼 누르지 말 것.** latest 이미지는 JWT_SECRET·Redis가 필요해서, EC2 `.env`와 compose를 바꾸기 전에 배포하면 앱이 안 뜬다. 절차는 docs/deploy.md.
- 알려진 한계(backlog에 기록): 여러 탭이 동시에 재발급하면 모두 로그아웃될 수 있다. 403은 CSRF 실패와 "본인 글 아님"을 화면에서 구분 못 한다(백엔드 에러 코드 추가 후보).
- 서브에이전트 쓰기 제한 훅(scripts/allow-only.sh)은 jq가 필요하다. 없으면 `winget install jqlang.jq`, `jq --version`이 안 되면 jq.exe를 PATH 폴더에 복사.
- Claude 앱이 대화마다 worktree(`.claude/worktrees/<이름>`, 브랜치 `claude/<이름>`)를 만들 수 있다. 그 안의 커밋은 main에 합쳐야 IntelliJ(main)에 보인다.
- Docker Desktop: 일시정지 버튼을 누르면 compose가 "manually paused"로 실패(재시작으로 해결). daemon 연결 에러면 Docker Desktop을 켠다.
- 포맷한 Windows에서 Docker가 안 켜지면: 관리자 터미널에서 `dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart` → `wsl --install --no-distribution`(손상 안내가 뜨면 아무 키로 복구) → 재부팅. PowerShell 7의 Enable-WindowsOptionalFeature는 "클래스가 등록되지 않았습니다"로 실패한다.
- 이 PC에서 처음 git push할 때 Git Credential Manager 로그인 창이 떠야 한다(사람이 직접 터미널에서 push해서 로그인). 한 번 하면 저장된다.
- EC2는 탄력적 IP가 없어 켤 때마다 퍼블릭 IP가 바뀐다. 다 쓰면 "중지"(종료 아님).
- 배포 방법: EC2 켜기 → Actions에서 main CI 초록 확인 → Actions → Deploy → Run workflow. 손 배포는 docs/deploy.md의 "비상용(수동)".
- GitHub Secrets(DOCKERHUB_USERNAME/TOKEN, AWS_ACCESS_KEY_ID/SECRET_ACCESS_KEY, EC2_SSH_KEY)와 Variables(AWS_REGION, EC2_INSTANCE_ID, EC2_SECURITY_GROUP_ID)는 등록돼 있다. 단 아래 키 교체가 끝나야 Deploy가 동작한다.
- main에 push할 때마다 Docker Hub에 커밋 SHA 태그가 하나씩 쌓인다.
- Windows에서 새 실행 스크립트를 커밋하면 실행 권한이 빠진다. 리눅스 러너에서 쓸 파일이면 `git update-index --chmod=+x <파일>`.
- Boot 4는 Jackson 3라 import가 `tools.jackson.databind`다(`com.fasterxml` 아님).
- 사용자 선호: 입문자라 새 도구·클래스는 비유 한 줄씩 몇 개만 소개. 백엔드에 관심이 많다. 보고는 짧게.
- 집 PC에 Herdr(터미널 보관 도구, `hc` 명령)를 설치했다. Claude Code CLI는 아직 설치 전일 수 있다(`claude` 명령).

## 집 PC 포맷 후 키 교체 (2026-11-04, 해킹 의심)
집 PC가 해킹당한 것 같아 초기화했다. 그 PC에 있던 키·비밀번호는 털렸다고 보고 **전부 새로 만들어 바꾼다.** 옛날 파일은 복구하지 말 것.

### 끝난 것
- 이메일·GitHub·AWS 루트 2단계 인증, GitHub 세션 정리.
- AWS IAM `github-actions-deploy` 액세스 키 **비활성화**(삭제는 안 함) → 지금 Deploy 버튼은 동작하지 않는다.
- 새 PC 세팅(Git, Java 21, Docker Desktop + WSL, Node.js, IntelliJ, jq), 프로젝트 새로 clone, `.env` 새 값으로 생성.
- **4. Docker Hub 토큰 교체 완료**: 새 토큰 `github-actions-2026-11`만 남기고 옛 토큰·Desktop 자동 토큰 전부 삭제, GitHub Secret `DOCKERHUB_TOKEN` 교체, CI로 새 토큰 동작 확인.

### 남은 것 (S4-f 배포 전에, EC2 켜기 전에)
1. **EC2 접속 키** (`demo-key.pem` 대체): 새 키를 만들어 EC2 Instance Connect(콘솔 브라우저 접속)로 서버 `~/.ssh/authorized_keys`에 넣고, **옛 `demo-key` 줄은 지운다.** AWS 콘솔에서 키 페어를 지워도 서버 안의 줄은 남는다. Instance Connect가 막히면 보안그룹 SSH 규칙을 잠깐 열어야 할 수 있다.
2. **배포 SSH 키** (`EC2_SSH_KEY`): docs/deploy.md 2)대로 새 `deploy-actions` 키를 만들고, 서버에 새 공개키를 넣고, **옛 `github-actions-deploy` 줄을 지운 뒤**, GitHub Secret `EC2_SSH_KEY`를 바꾼다. (옛 개인키는 포맷한 PC와 함께 사라졌다)
3. **AWS 액세스 키**: IAM에서 새 키를 발급하고 Secret `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`를 바꾼 뒤, 비활성화해 둔 옛 키를 **삭제**한다.
5. **EC2 DB 비밀번호**: 서버 `.env`의 `POSTGRES_PASSWORD`만 바꾸면 이미 만들어진 DB에는 적용되지 않는다. 컨테이너 안에서 `ALTER USER ... PASSWORD '...'`로 같이 바꾼다.
6. 끝나면 IAM 액세스 키의 "마지막 사용"과 GitHub Sessions·SSH keys를 한 번 더 확인한다. 키를 바꾸기 전에 마지막 사용 시각이 갱신돼 있으면 누가 쓴 것이다.
