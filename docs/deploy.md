# 배포 방법 (EC2, nginx 컨테이너)
브라우저 -> EC2 80번 -> nginx(`web` 컨테이너). nginx가 화면 파일을 주고,
`/api/...` 는 `/api`를 떼고 백엔드(`app:8080`)로, `/actuator/health` 는 그대로 넘깁니다.
화면과 API 주소가 같아서 CORS 설정이 필요 없습니다 (EC2 `.env`에 `CORS_ALLOWED_ORIGINS`는 넣지 않아도 됩니다).

## 자동 배포 (GitHub Actions 버튼)
이미지는 main에 push하면 CI가 Docker Hub에 올려 둡니다(`latest`). 배포는 아래처럼 버튼으로 합니다.
1. (AWS 콘솔) EC2 인스턴스를 켭니다(시작). 이 워크플로는 EC2를 켜거나 끄지 않습니다. 켜져 있지 않으면 실패합니다.
2. (EC2) DB 구조가 바뀌는 배포(테이블·컬럼 추가 등)라면 버튼을 누르기 전에 DB를 백업합니다. 아래 "배포 전 DB 백업"을 참고하세요.
   (GitHub) 버튼을 누르기 전에 Actions 탭에서 배포할 커밋의 CI(images 포함)가 초록색인지 확인합니다.
   자동 배포는 EC2의 `docker-compose.yml`을 바꾸지 않습니다. compose 파일을 바꿨다면 먼저 아래 비상용의 4~5단계(백업·scp)로 EC2에 복사한 뒤 버튼을 누릅니다.
   그다음 Actions 탭 -> 왼쪽 Deploy -> Run workflow -> main 선택 후 실행.
3. 워크플로가 하는 일: 켜진 EC2의 퍼블릭 IP 조회 -> 보안 그룹에 이 러너 IP의 SSH(22) 허용을 잠깐 추가
   -> SSH로 `docker compose pull`/`up -d` -> `/actuator/health`가 UP일 때까지 최대 약 3~4분 확인 -> (성공/실패 상관없이) SSH 허용 규칙 삭제.
4. 초록색이면 끝입니다. 확인용 주소는 `http://<퍼블릭IP>/`. 다 쓰고 나면 EC2를 콘솔에서 중지합니다.

만약 실행이 중간에 강제로 끊겨 `github-actions-deploy` 설명이 붙은 22번 규칙이 보안 그룹에 남았다면 콘솔에서 직접 지웁니다.

### 배포 전 DB 백업
앱이 시작될 때 `ddl-auto=update`로 테이블·컬럼이 자동으로 바뀝니다. 만약을 위해 바뀌기 전 상태를 파일로 남깁니다(`<날짜>`는 예: 20260101).
1. (EC2, `~/board`에서) 백업 파일을 만들고 나만 읽을 수 있게 합니다.
   - `set -a && . ./.env && set +a && docker exec board-postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" > backup-<날짜>.sql; unset POSTGRES_PASSWORD JWT_SECRET REDIS_PASSWORD; chmod 600 backup-<날짜>.sql`
   - 확인: `ls -l backup-<날짜>.sql`(0바이트가 아님), `grep -c "CREATE TABLE public.posts" backup-<날짜>.sql`(1)
2. (개발 PC) 서버 디스크가 망가질 때를 대비해 PC에도 복사합니다. 저장소 폴더 밖에 두고 커밋하지 않습니다.
   - `scp -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP>:~/board/backup-<날짜>.sql ~/board-backups/`
3. 되살리기(필요할 때만): 앱을 멈추고 빈 DB에 넣습니다.
   - `docker compose --profile prod stop app` 후 `docker exec -i board-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < backup-<날짜>.sql`
   - 같은 이름의 테이블이 이미 있으면 오류가 나므로, 실제로 되살릴 때는 DB를 비운 뒤 넣습니다.

### 처음 한 번 준비할 것
**1) IAM 사용자 + 액세스 키** (AWS 콘솔 -> IAM)
- 배포 전용 사용자를 만들고(콘솔 로그인 불필요) 아래 정책을 붙입니다. `<...>` 자리는 본인 값으로 바꿉니다. 실제 값은 이 파일에 적지 마세요.
- `DescribeInstances`는 AWS 규칙상 대상을 제한할 수 없어 `"*"`입니다. 보안 그룹 규칙 추가/삭제는 해당 보안 그룹 하나로만 제한합니다.
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "ec2:DescribeInstances",
      "Resource": "*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ec2:AuthorizeSecurityGroupIngress",
        "ec2:RevokeSecurityGroupIngress"
      ],
      "Resource": "arn:aws:ec2:<리전>:<계정ID>:security-group/<보안그룹ID>"
    }
  ]
}
```
- 그 사용자의 "보안 자격 증명" 탭에서 액세스 키를 만들고, Access key ID와 Secret access key를 따로 복사해 둡니다(비밀키는 다시 볼 수 없음).

**2) 배포 전용 SSH 키 쌍** (개인 PC 터미널)
- `ssh-keygen -t ed25519 -f ~/.ssh/deploy-actions -C github-actions-deploy -N ""`
- 공개키(`~/.ssh/deploy-actions.pub`) 한 줄을 EC2의 `~/.ssh/authorized_keys` 맨 아래에 추가합니다(기존 줄은 지우지 않음).
  예: 기존 키로 접속해 `echo '<공개키 한 줄>' >> ~/.ssh/authorized_keys`
- 개인키(`~/.ssh/deploy-actions`) 파일 내용 전체(`-----BEGIN` 줄부터 `END` 줄까지)를 아래 Secret `EC2_SSH_KEY`에 넣습니다. 개인키는 다른 곳에 붙여넣거나 커밋하지 않습니다.

**3) GitHub 저장소 설정** (Settings -> Secrets and variables -> Actions)
- Secrets 탭 (비밀)
  - `AWS_ACCESS_KEY_ID`: 1)에서 만든 Access key ID
  - `AWS_SECRET_ACCESS_KEY`: 1)에서 만든 Secret access key
  - `EC2_SSH_KEY`: 2)의 개인키 파일 내용 전체
- Variables 탭 (비밀 아님)
  - `AWS_REGION`: EC2가 있는 리전 코드
  - `EC2_INSTANCE_ID`: 인스턴스 ID (`i-`로 시작)
  - `EC2_SECURITY_GROUP_ID`: EC2에 붙은 보안 그룹 ID (`sg-`로 시작)

## 비상용 (수동)
Actions를 쓸 수 없을 때 개발 PC에서 손으로 하는 방법입니다.

EC2는 이미지를 받아 실행만 합니다. 저장소를 clone할 필요 없이, EC2에는 `docker-compose.yml` 하나만 복사해 두면 됩니다.

전제:
- EC2의 `~/board` 폴더에 `.env`가 있고 `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` 값이 들어 있어야 합니다.
  `.env`는 서버에만 두고, 복사하거나 커밋하지 않습니다.
- S4(로그인)부터 같은 `.env`에 아래 값도 필요합니다.
  - `JWT_SECRET` (필수): 로그인 토큰 서명용 비밀키. Base64 문자열(디코딩 후 32바이트 이상)이며 서버에서 직접 만듭니다: `openssl rand -base64 32`. 없으면 서버가 시작되지 않습니다.
  - `REDIS_PASSWORD` (필수): 리프레시 토큰 저장소(Redis) 비밀번호. 서버에서 직접 만듭니다: `openssl rand -hex 24`. 없으면 `docker compose`가 바로 실패합니다.
  - `COOKIE_SECURE` (선택): 로그인 쿠키의 Secure 속성. 기본 `false`이고, HTTPS를 도입하면 `true`로 바꿉니다.
- DB 비밀번호를 바꿀 때: `.env`의 `POSTGRES_PASSWORD`만 바꾸면 이미 만들어진 DB에는 적용되지 않습니다(처음 만들 때만 읽음).
  EC2 `~/board`에서 DB 안의 비밀번호와 `.env`를 같은 값으로 함께 바꿉니다. 값은 서버에서 만들고 화면에 출력하지 않습니다.
  - `set -a && . ./.env && set +a && NEWPW=$(openssl rand -hex 24)`
  - `docker exec board-postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "ALTER USER \"$POSTGRES_USER\" PASSWORD '$NEWPW';"`
  - `sed -i "s/^POSTGRES_PASSWORD=.*/POSTGRES_PASSWORD=$NEWPW/" .env && unset NEWPW POSTGRES_PASSWORD`
  - 떠 있는 app은 기존 연결로 계속 돌지만, 새로 연결할 때는 새 값이 필요하므로 이어서 `docker compose --profile prod up -d`로 다시 띄웁니다.
- 주의: S4부터 `docker-compose.yml`이 바뀌었습니다(위 환경변수 전달). S4를 처음 배포할 때는 변경된 `docker-compose.yml`을 EC2의 `~/board`로 먼저 `scp`해야 합니다.
- 백엔드 이미지 `seokam36/demo:latest`가 Docker Hub에 올라가 있어야 합니다.
- 퍼블릭 IP는 인스턴스를 시작할 때마다 바뀝니다 (탄력적 IP를 쓰지 않는 경우). 아래 `<퍼블릭IP>`는 그때그때 확인한 값으로 바꿉니다.

1. (개발 PC) 이미지 빌드 (빌드 컨텍스트는 `frontend/`)
   - `docker build -t seokam36/demo-web:latest frontend`
   - 백엔드 코드도 바뀌었다면 저장소 루트에서 `docker build -t seokam36/demo:latest .`
   - 개발 PC와 EC2(t3.micro, x86_64)의 아키텍처가 다르면(예: Apple Silicon) `--platform linux/amd64`를 붙입니다.
   - API 주소는 빌드 인자 `VITE_API_BASE_URL`(기본값 `/api`)로 바뀝니다. 보통은 바꿀 필요가 없습니다.
2. (개발 PC) Docker Hub에 올리기: `docker login` 후 `docker push seokam36/demo-web:latest`
   (백엔드도 바꿨다면 `docker push seokam36/demo:latest`도)
3. (AWS 콘솔) 보안 그룹 인바운드에 80(HTTP)이 열려 있는지 확인합니다. 예전에 8080을 열었다면 이제 닫아도 됩니다 (app은 외부 접근 안 함).
   - SSH(22)는 내 PC IP만 허용합니다. PC 터미널에서 접속 키 `~/.ssh/board-ec2`(비밀번호 걸린 ed25519 키)로 `ssh` 합니다.
   - 와이파이·장소가 바뀌어 IP가 바뀌면 보안 그룹의 SSH 소스를 "내 IP"로 다시 저장합니다.
   - 접속 키는 PC마다 따로 만듭니다(`ssh-keygen -t ed25519 -f ~/.ssh/board-ec2 -C board-ec2`). 개인키 파일을 다른 PC로 복사하지 않고, 새 PC의 공개키 한 줄을 EC2 `~/.ssh/authorized_keys`에 추가합니다.
   - 키가 없거나 막혔을 때(비상): 보안 그룹에 SSH 규칙을 하나 추가합니다(소스: 접두사 목록 `com.amazonaws.<리전>.ec2-instance-connect`, IPv4용).
     그다음 콘솔 -> 인스턴스 -> 연결 -> "EC2 인스턴스 연결"로 브라우저 터미널을 엽니다. 작업이 끝나면 그 규칙을 바로 지웁니다.
   - Windows PowerShell에서 `ssh`를 못 찾으면 `& "C:\Windows\System32\OpenSSH\ssh.exe"`처럼 전체 경로로 실행하거나 Git Bash를 씁니다. PowerShell 창 안의 ssh에서는 붙여넣기가 마우스 오른쪽 클릭입니다(Ctrl+V 아님).
4. (개발 PC) EC2의 기존 compose 파일을 백업합니다 (되돌리기용, `<날짜>`는 예: 20260101)
   - `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP> 'cp ~/board/docker-compose.yml ~/board/docker-compose.yml.bak-<날짜>'`
5. (개발 PC, 저장소 루트에서) 새 compose 파일을 EC2로 복사합니다.
   - `scp -i ~/.ssh/board-ec2 docker-compose.yml ec2-user@<퍼블릭IP>:~/board/docker-compose.yml`
6. (EC2) 접속해서 새 이미지로 실행합니다.
   - `ssh -i ~/.ssh/board-ec2 ec2-user@<퍼블릭IP>`
   - `cd ~/board && docker compose --profile prod pull && docker compose --profile prod up -d`
   - `docker ps`로 `board-web`, `board-app`, `board-postgres`, `board-redis` 네 컨테이너가 떠 있는지 확인합니다.
7. 브라우저에서 아래를 확인합니다.
   - `http://<퍼블릭IP>/`: 목록 화면이 뜨는지
   - `http://<퍼블릭IP>/posts/1`: 새로고침해도 JSON이 아니라 화면이 뜨는지
   - `http://<퍼블릭IP>/api/posts`: JSON 배열이 오는지 (health는 app만 살아 있어도 UP이라 `/api` 전달까지는 확인하지 못합니다)
   - `http://<퍼블릭IP>/actuator/health`: UP인지 (DB와 Redis 상태도 포함됩니다. 둘 중 하나가 죽거나 `REDIS_PASSWORD`가 틀리면 503이 되고, 자동 배포도 실패로 끝납니다. 로그인이 실제로 안 되는 상태이므로 의도된 동작입니다)
   - `http://<퍼블릭IP>:8080`: 접속되지 않는지 (app은 외부에 열지 않습니다)

   `/api/posts`가 502이면 app이 부팅 중일 수 있으니 잠시 후 다시 시도하고,
   계속되면 `docker compose --profile prod ps`로 app 상태를 보고 `docker compose --profile prod logs app`으로 오류를 확인합니다.

되돌리기: 문제가 생기면 EC2에서 백업 파일을 되돌리고 다시 실행합니다.
- `cd ~/board && cp docker-compose.yml.bak-<날짜> docker-compose.yml && docker compose --profile prod up -d --remove-orphans`
- `--remove-orphans`: 백업 파일에 없는 컨테이너(예: `board-web`)를 같이 내립니다. 없으면 남은 컨테이너가 80번 포트를 붙잡아 app이 시작하지 못할 수 있습니다.
- 되돌리는 것은 compose 설정뿐입니다. 이미지는 `latest` 태그라 push할 때 Docker Hub의 옛 이미지가 덮어써지므로, 되돌려도 새 이미지가 그대로 쓰입니다.
  (이미지까지 되돌리고 싶다면 push 전에 옛 이미지에 다른 태그를 붙여 두어야 합니다.)
