# 작업 인수인계

컴퓨터를 바꿔도 이어서 작업하기 위한 메모. 작업을 끝낼 때 /handoff 로 갱신한다.

마지막 갱신: 2026-11-03 (집 PC, 다음 작업은 노트북)

## 지금 어디까지
- S2(게시글 수정 + 삭제) 완료. 게시판 CRUD가 서버에서 모두 동작한다.
- S3(GitHub Actions CI/CD) 완료: S3-a CI, S3-b 이미지 자동 push, S3-c 버튼 배포까지 끝. Deploy 버튼으로 첫 자동 배포 성공.
- 작업 방식: 배포는 큰 기능(S번호) 끝날 때 한 번만. planner 결과·review [필수 수정]·실패 2회 초과·커밋/push/배포 전에만 멈춘다.

## 다음에 할 일
- **S4. 회원가입 + 로그인** — backlog에 추가만 해 둠. "S4 시작"으로 planner부터.
  - planner에서 정할 것: 세션 vs JWT, 단계 나누기(예: 회원가입 API → 로그인 → 글과 작성자 연결 → 화면), 기존 글(작성자 없음) 처리, 내 글만 수정·삭제할지.
  - Spring Security 같은 새 의존성이 들어가면 research로 공식 문서부터 확인하고, 보고에 "추가한 의존성과 이유"를 넣는다.

## 진행 중인 단계
- 없음 (커밋 안 된 변경 없음)

## 주의할 점
- 새 컴퓨터에서는 먼저 `git pull`. 끝나면 커밋·push.
- `.env`(DB 접속 정보)는 git에 없다. 처음 쓰는 컴퓨터에서는 `.env.example`을 복사해 `.env`를 만들고 값을 채운다.
- `./gradlew bootRun`은 `.env`를 자동으로 읽지 않는다. PowerShell에서 환경변수로 불러온 뒤 실행한다.
- Docker 명령이 daemon 연결 에러를 내면 Docker Desktop을 켠다.
- EC2는 탄력적 IP가 없어 켤 때마다 퍼블릭 IP가 바뀐다. 다 쓰면 "중지"(종료 아님).
- 배포 방법이 바뀌었다: EC2 켜기 → Actions에서 main CI 초록 확인 → Actions → Deploy → Run workflow. 손 배포는 docs/deploy.md의 "비상용(수동)".
- 기록 파일 정리(2026-11-03): changelog를 없애고 backlog "끝난 일" 표로 합쳤다(옛 기록은 docs/archive/). recorder 에이전트 없음. 배포 문서는 docs/deploy.md.
- docker-compose.yml을 바꾸면 Deploy 버튼만으로는 EC2에 반영되지 않는다. 먼저 docs/deploy.md 비상용 단계대로 scp로 복사한다.
- GitHub Secrets(DOCKERHUB_USERNAME/TOKEN, AWS_ACCESS_KEY_ID/SECRET_ACCESS_KEY, EC2_SSH_KEY)와 Variables(AWS_REGION, EC2_INSTANCE_ID, EC2_SECURITY_GROUP_ID)는 등록 완료. Variables에는 안내 문구나 예시가 아니라 콘솔에서 복사한 실제 값을 넣는다.
- 배포용 SSH 개인키(`~/.ssh/deploy-actions`)는 집 PC에만 있다. GitHub에 이미 등록돼 있어 노트북에는 필요 없다.
- main에 push할 때마다(문서만 바꿔도) Docker Hub에 커밋 SHA 태그가 하나씩 쌓인다. 옛 백업 태그 `s1-d`는 이제 SHA 태그가 대신하니 정리해도 된다.
- Windows에서 새 실행 스크립트를 커밋하면 실행 권한이 빠진다. 리눅스 러너에서 쓸 파일이면 `git update-index --chmod=+x <파일>`.
- 아직 실제로 안 해 본 것: EC2가 꺼진 상태에서 Deploy를 눌렀을 때 첫 단계에서 실패하고 보안그룹이 그대로인지.
