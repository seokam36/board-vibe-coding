# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-04 (노트북, 다음 작업은 집 PC)

## 지금 어디까지
- S0~S3 완료. 게시판 CRUD + CI/CD(버튼 배포)까지 서버에서 동작한다.
- S4(회원가입 + 로그인) 진행 중: S4-a(Spring Security + 회원가입), S4-b(JWT 로그인·로그아웃·내 정보) 완료. 테스트 123개 통과.
- 작업 방식: 배포는 큰 기능(S번호) 끝날 때 한 번만. planner 결과·review [필수 수정]·실패 2회 초과·커밋/push/배포 전에만 멈춘다.

## 다음에 할 일
- **S4-c 시작** — Redis 도입 + 리프레시 토큰(발급·재발급·로그아웃 시 삭제). 계획은 승인됨, planner 없이 research(Redis 이미지 버전·라이선스, Testcontainers Redis) → build부터.

## 진행 중인 단계
- S4 (S4-a, S4-b 끝 / S4-c ~ S4-f 남음). 단계 목록은 docs/backlog.md.
- 승인된 결정 (S4 전체):
  - 액세스 토큰: JWT HS256, 15분, HttpOnly 쿠키 `access_token`, Redis에 저장 안 함
  - 리프레시 토큰: 랜덤 문자열, 7일, HttpOnly 쿠키(Path=/api/auth), Redis에 SHA-256 해시로 저장(TTL), 재발급 때 새것으로 교체(회전), 로그아웃 시 삭제. 여러 기기 동시 로그인 허용. 블랙리스트 없음
  - CSRF 켬(csrf.spa(), XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더). 세션 STATELESS
  - 권한(S4-d): 목록·상세 누구나, 글쓰기 로그인, 수정·삭제 본인만. 기존 글은 작성자 null, 수정·삭제 불가. 관리자/역할은 범위 밖
  - 화면(S4-e): 로컬은 Vite 프록시(/api → 8080), 액세스 만료 시 자동 재발급, "실제 비밀번호 쓰지 말 것(HTTP)" 안내
  - Redis: 비밀번호 걸기, 디스크 저장 안 함, mem_limit 64m / maxmemory 32mb
  - 공개 경로에 만료 토큰이 붙어 와도 무시(200)
- 커밋 안 된 변경: 없음

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- `.env`(DB 접속 정보)는 git에 없다. 처음 쓰는 컴퓨터에서는 `.env.example`을 복사해 `.env`를 만들고 값을 채운다.
- **`.env`에 `JWT_SECRET`이 새로 필요하다**(32바이트 이상 Base64, 만드는 법은 `.env.example`). 없으면 서버가 "비밀키가 비어 있습니다"로 시작을 멈춘다. 테스트는 키를 자동 생성하니 필요 없다.
- `./gradlew bootRun`은 `.env`를 자동으로 읽지 않는다. PowerShell에서 DB_*와 JWT_SECRET을 환경변수로 넣고 실행한다.
- **S4-f 전까지 Deploy 버튼 누르지 말 것.** main의 latest 이미지는 이제 JWT_SECRET이 필수라, EC2 `.env`와 compose 파일을 바꾸기 전에 배포하면 앱이 안 뜬다. 절차는 docs/deploy.md.
- 프론트는 S4-e 전까지 글쓰기가 CSRF 403으로 실패한다(의도된 상태).
- 서브에이전트 쓰기 제한 훅(scripts/allow-only.sh)은 **jq가 필요하다.** 없으면 tester·deploy가 아무 파일도 못 쓴다. `winget install jqlang.jq` 후 `jq --version`이 안 되면, 설치된 jq.exe를 PATH 폴더(예: `C:\Users\<사용자>\bin`)에 복사한다.
- Claude 앱이 대화마다 worktree(`.claude/worktrees/<이름>`, 브랜치 `claude/<이름>`)를 만들 수 있다. 그 안에서 한 커밋은 main에 합쳐야 IntelliJ(main)에 보인다. 훅은 원래 폴더의 scripts/allow-only.sh를 실행한다.
- Docker Desktop 왼쪽 아래 일시정지 버튼을 누르면 compose가 "manually paused"로 실패한다. Resume이 안 먹으면 Docker Desktop 재시작.
- Docker 명령이 daemon 연결 에러를 내면 Docker Desktop을 켠다.
- EC2는 탄력적 IP가 없어 켤 때마다 퍼블릭 IP가 바뀐다. 다 쓰면 "중지"(종료 아님).
- 배포 방법: EC2 켜기 → Actions에서 main CI 초록 확인 → Actions → Deploy → Run workflow. 손 배포는 docs/deploy.md의 "비상용(수동)".
- docker-compose.yml을 바꾸면 Deploy 버튼만으로는 EC2에 반영되지 않는다. 먼저 docs/deploy.md 비상용 단계대로 scp로 복사한다. (S4-b에서 compose가 바뀌었다)
- GitHub Secrets(DOCKERHUB_USERNAME/TOKEN, AWS_ACCESS_KEY_ID/SECRET_ACCESS_KEY, EC2_SSH_KEY)와 Variables(AWS_REGION, EC2_INSTANCE_ID, EC2_SECURITY_GROUP_ID)는 등록 완료. Variables에는 콘솔에서 복사한 실제 값을 넣는다.
- 배포용 SSH 개인키(`~/.ssh/deploy-actions`)는 집 PC에만 있다.
- main에 push할 때마다 Docker Hub에 커밋 SHA 태그가 하나씩 쌓인다.
- Windows에서 새 실행 스크립트를 커밋하면 실행 권한이 빠진다. 리눅스 러너에서 쓸 파일이면 `git update-index --chmod=+x <파일>`.
- Boot 4는 Jackson 3라 import가 `tools.jackson.databind`다(`com.fasterxml` 아님).
- 사용자 선호: 단계 보고는 짧게, 자세한 설명은 S4가 끝날 때 한 번에 쉽게 정리. Notion 기록도 S4 끝에 S4-b부터 이어서 쓴다(S4-a는 작성 완료).
- 아직 실제로 안 해 본 것: EC2가 꺼진 상태에서 Deploy를 눌렀을 때 첫 단계에서 실패하고 보안그룹이 그대로인지.
